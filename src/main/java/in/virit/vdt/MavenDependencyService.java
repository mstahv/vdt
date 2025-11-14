package in.virit.vdt;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.open.Open;
import io.quarkus.vertx.http.HttpServerStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for resolving Maven dependencies using mvn dependency:tree command.
 */
@ApplicationScoped
public class MavenDependencyService {

    // Cache for artifact sizes (groupId:artifactId:version -> size in bytes)
    private final Map<String, Long> sizeCache = new java.util.concurrent.ConcurrentHashMap<>();


    @ConfigProperty(name = "vdt.deployed", defaultValue = "false")
    boolean deployed;

    public void httpStarted(
            @ObservesAsync HttpServerStart start,
            @ConfigProperty(name = "quarkus.http.port", defaultValue="8080") int port) {
        if(!deployed) {
            Open.open("http://localhost:" + port);
        }
    }

    public boolean isLocal() {
        return !deployed;
    }

    /**
     * Resolves dependencies from Maven coordinates (groupId:artifactId:version).
     * Downloads the POM file and uses mvn dependency:tree for accurate resolution.
     */
    public DependencyNode resolveDependencies(String coordinates) throws Exception {
        String[] parts = coordinates.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid coordinates format. Expected groupId:artifactId:version");
        }

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];

        return resolveDependencies(groupId, artifactId, version);
    }

    /**
     * Resolves dependencies for a given artifact by downloading its POM and using mvn.
     */
    public DependencyNode resolveDependencies(String groupId, String artifactId, String version) throws Exception {
        // Download the POM file for this artifact
        String pomContent = downloadPom(groupId, artifactId, version);

        // Use the mvn command to resolve dependencies
        return resolveDependenciesFromPom(pomContent);
    }

    /**
     * Downloads a POM file from Maven Central for the given coordinates.
     */
    private String downloadPom(String groupId, String artifactId, String version) throws Exception {
        String pomUrl = "https://repo.maven.apache.org/maven2/"
                + groupId.replace('.', '/') + "/"
                + artifactId + "/"
                + version + "/"
                + artifactId + "-" + version + ".pom";

        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(pomUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                    return content.toString();
                }
            } else {
                throw new Exception("Failed to download POM from " + pomUrl +
                    " (HTTP " + conn.getResponseCode() + ")");
            }
        } catch (IOException e) {
            throw new Exception("Failed to download POM from " + pomUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses a pom.xml file and resolves its dependencies.
     * Uses ProcessBuilder to run mvn dependency:tree -Dverbose=true for accurate verbose output.
     */
    public DependencyNode resolveDependenciesFromPom(String pomContent) throws Exception {
        // Create temporary directory for pom.xml
        UUID uuid = UUID.randomUUID();

        Path tempDir = Files.createDirectory(Path.of("mvntemp"+ uuid.toString()));
        Path pomFile = tempDir.resolve("pom.xml");
        pomFile.getParent().toFile().mkdirs();

        try {
            // Write POM content to temporary file
            Files.write(pomFile, pomContent.getBytes(StandardCharsets.UTF_8));

            // Run mvn dependency:tree -Dverbose=true
            ProcessBuilder pb = new ProcessBuilder(
                "mvn", "dependency:tree", "-Dverbose=true"
            );
            if(Files.exists(Path.of("/etc/os-release"))) {
                // This is for linux server, figure out how to detect jbang execution
                pb = new ProcessBuilder("/usr/local/sdkman/candidates/maven/current/bin/mvn",
                        "dependency:tree", "-Dverbose=true");
                // Modify PATH if necessary
                Map<String, String> env = pb.environment();
                env.put("PATH", "/usr/local/sdkman/candidates/maven/current/bin:/usr/local/sdkman/candidates/java/current/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin");
                env.put("MAVEN_HOME", "/usr/local/sdkman/candidates/maven/current");
                env.put("JAVA_HOME", "/usr/local/sdkman/candidates/java/current");
            }
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Read output
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new Exception("Maven command failed with exit code " + exitCode + ":\n" + output.toString());
            }

            // Parse the dependency tree from Maven output
            return parseMavenTreeOutput(output.toString());

        } finally {
            // Clean up temporary files
            Files.delete(pomFile);
            Files.delete(tempDir);
        }
    }

    /**
     * Parses Maven dependency:tree output and builds DependencyNode tree.
     */
    private DependencyNode parseMavenTreeOutput(String output) throws Exception {
        // Note uses raw text output as the json format doesn't include all the same data
        String[] lines = output.split("\n");

        // Find the start of the dependency tree
        int startLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Look for the root dependency line
            if (line.contains(":jar:") || line.contains(":war:") || line.contains(":pom:")) {
                // Skip [INFO] lines, find actual tree start
                if (!line.trim().startsWith("[INFO]") || line.contains("---")) {
                    continue;
                }
                // This is likely the root
                String cleaned = line.substring(line.indexOf("]") + 1).trim();
                if (cleaned.matches(".*:.*:.*:.*")) {
                    startLine = i;
                    break;
                }
            }
        }

        if (startLine == -1) {
            throw new Exception("Could not find dependency tree in Maven output");
        }

        // Parse root node
        String rootLine = lines[startLine].substring(lines[startLine].indexOf("]") + 1).trim();
        DependencyNode root = parseTreeLine(rootLine, 0);

        // Parse children using stack to track hierarchy
        Stack<DependencyNode> nodeStack = new Stack<>();
        Stack<Integer> depthStack = new Stack<>();
        nodeStack.push(root);
        depthStack.push(0);

        for (int i = startLine + 1; i < lines.length; i++) {
            String line = lines[i];

            // Skip non-dependency lines
            if (!line.contains("[INFO]") || !line.contains(":")) {
                continue;
            }

            // Check if this is end of tree
            if (line.contains("---") || line.contains("BUILD")) {
                break;
            }

            // Extract the dependency line after [INFO]
            int infoEnd = line.indexOf("]");
            if (infoEnd == -1) continue;

            String depLine = line.substring(infoEnd + 1);

            // Calculate depth from tree characters (+-, |, \-, spaces)
            int depth = calculateDepth(depLine);

            // Clean the line (remove tree characters)
            String cleanedLine = depLine.replaceFirst("^[\\s|+\\\\-]+", "").trim();

            if (cleanedLine.isEmpty() || !cleanedLine.contains(":")) {
                continue;
            }

            // Parse the dependency
            DependencyNode node = parseTreeLine(cleanedLine, depth);

            // Pop stack until we find the parent
            while (!depthStack.isEmpty() && depthStack.peek() >= depth) {
                nodeStack.pop();
                depthStack.pop();
            }

            // Add as child to current parent
            if (!nodeStack.isEmpty()) {
                nodeStack.peek().addChild(node);
                nodeStack.push(node);
                depthStack.push(depth);
            }
        }

        return root;
    }

    /**
     * Calculates tree depth from indentation characters.
     */
    private int calculateDepth(String line) {
        int depth = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '+' || c == '\\' || c == '-') {
                depth++;
                break;
            } else if (c == '|') {
                depth++;
            } else if (c != ' ') {
                break;
            }
        }
        return depth;
    }

    /**
     * Parses a single dependency line from Maven tree output.
     * Two formats:
     * 1. groupId:artifactId:packaging:version:scope (annotation text)
     * 2. (groupId:artifactId:packaging:version:scope - omitted for ...)
     */
    private DependencyNode parseTreeLine(String line, int depth) {
        String coords;
        String annotations = "";

        // Check format: does line start with '(' (omitted format)?
        if (line.startsWith("(") && line.contains(")")) {
            // Format: (coords - annotations)
            String content = line.substring(1, line.lastIndexOf(")"));
            int dashIdx = content.indexOf(" - ");
            if (dashIdx > 0) {
                coords = content.substring(0, dashIdx).trim();
                annotations = content.substring(dashIdx + 3).trim();
            } else {
                coords = content.trim();
            }
        } else {
            // Format: coords (annotations)
            int parenIdx = line.indexOf(" (");
            if (parenIdx > 0) {
                coords = line.substring(0, parenIdx).trim();
                int closeIdx = line.lastIndexOf(")");
                if (closeIdx > parenIdx) {
                    annotations = line.substring(parenIdx + 2, closeIdx).trim();
                }
            } else {
                coords = line.trim();
            }
        }

        // Parse coordinates: groupId:artifactId:packaging:version:scope
        String[] parts = coords.split(":");
        if (parts.length < 3) {
            // Fallback for malformed line
            return new DependencyNode("unknown", "unknown", "unknown");
        }

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts.length > 3 ? parts[3] : "unknown";
        String scope = parts.length > 4 ? parts[4] : "compile";

        DependencyNode node = new DependencyNode(groupId, artifactId, version);
        node.setScope(scope);

        // Parse annotations
        if (!annotations.isEmpty()) {
            // Check for optional
            if (annotations.contains("optional")) {
                node.setOptional(true);
            }

            // Check for omitted
            if (annotations.contains("omitted for")) {
                node.setOmitted(true);
                // Extract omission reason
                Pattern omittedPattern = Pattern.compile("omitted for ([^;]+)");
                Matcher matcher = omittedPattern.matcher(annotations);
                if (matcher.find()) {
                    node.setOmittedReason(matcher.group(1).trim());
                }
            }

            // Check for version management notes
            if (annotations.contains("version managed from")) {
                Pattern versionPattern = Pattern.compile("version managed from ([^;]+)");
                Matcher matcher = versionPattern.matcher(annotations);
                if (matcher.find()) {
                    String note = "version managed from " + matcher.group(1).trim();
                    if (node.getNotes() != null && !node.getNotes().contains(note)) {
                        node.setNotes(node.getNotes() + "; " + note);
                    } else if (node.getNotes() == null) {
                        node.setNotes(note);
                    }
                }
            }

            // Check for scope management
            if (annotations.contains("scope managed from")) {
                Pattern scopePattern = Pattern.compile("scope managed from ([^;]+)");
                Matcher matcher = scopePattern.matcher(annotations);
                if (matcher.find()) {
                    String note = "scope managed from " + matcher.group(1).trim();
                    if (node.getNotes() != null) {
                        node.setNotes(node.getNotes() + "; " + note);
                    } else {
                        node.setNotes(note);
                    }
                }
            }
        }

        return node;
    }

    /**
     * Gets the size of an artifact in bytes. Returns 0 if not a jar or if size cannot be determined.
     * Checks local Maven repository first, then makes HTTP HEAD request to Maven Central if needed.
     */
    public long getArtifactSize(String groupId, String artifactId, String version) {
        String cacheKey = groupId + ":" + artifactId + ":" + version;

        // Check cache first
        if (sizeCache.containsKey(cacheKey)) {
            return sizeCache.get(cacheKey);
        }

        // Only calculate size for jar artifacts
        long size = 0;

        // First, try local Maven repository
        String userHome = System.getProperty("user.home");
        String localPath = userHome + "/.m2/repository/"
                + groupId.replace('.', '/') + "/"
                + artifactId + "/"
                + version + "/"
                + artifactId + "-" + version + ".jar";

        File localFile = new File(localPath);
        if (localFile.exists() && localFile.isFile()) {
            size = localFile.length();
        } else {
            // Fall back to HTTP HEAD request to Maven Central
            try {
                String mavenCentralUrl = "https://repo.maven.apache.org/maven2/"
                        + groupId.replace('.', '/') + "/"
                        + artifactId + "/"
                        + version + "/"
                        + artifactId + "-" + version + ".jar";

                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(mavenCentralUrl).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    size = conn.getContentLengthLong();
                }
                conn.disconnect();
            } catch (Exception e) {
                // If we can't get the size, just leave it as 0
            }
        }

        // Cache the result
        sizeCache.put(cacheKey, size);
        return size;
    }

    /**
     * Formats a size in bytes to a human-readable string (bytes, KB, MB, GB).
     */
    public static String formatSize(long bytes) {
        if (bytes == 0) {
            return "-";
        } else if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Calculates the total size of a dependency and all its transitive dependencies.
     * Skips omitted dependencies as they are not actually included in the project.
     */
    public long calculateTotalSize(DependencyNode node) {
        long total = getArtifactSize(node.getGroupId(), node.getArtifactId(), node.getVersion());

        if (node.getChildren() != null) {
            for (DependencyNode child : node.getChildren()) {
                // Skip omitted dependencies as they are not actually included
                if (!child.isOmitted()) {
                    total += calculateTotalSize(child);
                }
            }
        }

        return total;
    }
}
