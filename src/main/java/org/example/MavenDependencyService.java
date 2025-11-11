package org.example;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.*;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for resolving Maven dependencies using Maven Resolver API.
 */
@Service
public class MavenDependencyService {

    private final RepositorySystem repositorySystem;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    public MavenDependencyService() {
        // Initialize Maven Resolver components
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

        this.repositorySystem = locator.getService(RepositorySystem.class);
        this.session = MavenRepositorySystemUtils.newSession();

        // Use user's local Maven repository or default
        String userHome = System.getProperty("user.home");
        LocalRepository localRepo = new LocalRepository(userHome + "/.m2/repository");
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepo));

        // Configure remote repositories
        this.repositories = Arrays.asList(
                new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build()
        );
    }

    /**
     * Resolves dependencies from Maven coordinates (groupId:artifactId:version).
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
     * Resolves dependencies for a given artifact.
     */
    public DependencyNode resolveDependencies(String groupId, String artifactId, String version) throws Exception {
        return resolveDependencies(groupId, artifactId, version, "compile");
    }

    /**
     * Resolves dependencies for a given artifact with a specific scope.
     */
    public DependencyNode resolveDependencies(String groupId, String artifactId, String version, String scope) throws Exception {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
        Dependency dependency = new Dependency(artifact, scope != null ? scope : "compile");

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(dependency);
        collectRequest.setRepositories(repositories);

        DependencyRequest dependencyRequest = new DependencyRequest();
        dependencyRequest.setCollectRequest(collectRequest);

        try {
            org.eclipse.aether.resolution.DependencyResult result = repositorySystem.resolveDependencies(session, dependencyRequest);
            return buildDependencyTree(result.getRoot());
        } catch (DependencyResolutionException e) {
            throw new Exception("Failed to resolve dependencies: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a pom.xml file and resolves its dependencies using mvn dependency:tree -Dverbose.
     * This ensures we get exactly the same output as Maven including omitted dependencies.
     */
    public DependencyNode resolveDependenciesFromPom(String pomContent) throws Exception {
        return resolveDependenciesFromPomViaCommand(pomContent);
    }

    /**
     * Parses pom.xml using mvn dependency:tree command to get verbose dependency information.
     */
    private DependencyNode resolveDependenciesFromPomViaCommand(String pomContent) throws Exception {
        // Create temporary directory for pom.xml
        File tempDir = Files.createTempDirectory("maven-dep-analysis").toFile();
        File pomFile = new File(tempDir, "pom.xml");

        try {
            // Write POM content to temporary file
            Files.write(pomFile.toPath(), pomContent.getBytes(StandardCharsets.UTF_8));

            // Run mvn dependency:tree -Dverbose=true
            ProcessBuilder pb = new ProcessBuilder(
                "mvn", "dependency:tree", "-Dverbose=true"
            );
            pb.directory(tempDir);
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
            pomFile.delete();
            tempDir.delete();
        }
    }

    /**
     * Original method that parses pom.xml using Maven APIs (without omitted dependencies).
     */
    private DependencyNode resolveDependenciesFromPomOld(String pomContent) throws Exception {
        try {
            // Create a ModelResolver for resolving parent POMs
            ModelResolver modelResolver = new SimpleModelResolver(repositorySystem, session, repositories);

            // Parse the POM file
            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
            request.setModelSource(new StringModelSource(pomContent));
            request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
            request.setSystemProperties(System.getProperties());
            request.setModelResolver(modelResolver);

            DefaultModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
            ModelBuildingResult result = modelBuilder.build(request);
            Model model = result.getEffectiveModel();

            // Create root node for the project
            DependencyNode root = new DependencyNode(
                    model.getGroupId() != null ? model.getGroupId() : model.getParent().getGroupId(),
                    model.getArtifactId(),
                    model.getVersion() != null ? model.getVersion() : model.getParent().getVersion()
            );

            // Build a map of managed dependencies
            java.util.Map<String, String> managedVersions = new java.util.HashMap<>();
            if (model.getDependencyManagement() != null && model.getDependencyManagement().getDependencies() != null) {
                for (org.apache.maven.model.Dependency managed : model.getDependencyManagement().getDependencies()) {
                    String key = managed.getGroupId() + ":" + managed.getArtifactId();
                    managedVersions.put(key, managed.getVersion());
                }
            }

            // Resolve each dependency
            for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
                try {
                    String scope = dep.getScope() != null ? dep.getScope() : "compile";
                    String version = dep.getVersion();

                    // Check if version comes from dependency management
                    String key = dep.getGroupId() + ":" + dep.getArtifactId();
                    String managedVersion = managedVersions.get(key);

                    DependencyNode depNode = resolveDependencies(
                            dep.getGroupId(),
                            dep.getArtifactId(),
                            version != null ? version : managedVersion,
                            scope
                    );
                    depNode.setOptional("true".equals(dep.getOptional()));

                    // Add note if version is managed
                    if (version == null && managedVersion != null) {
                        depNode.setNotes("version managed from " + managedVersion);
                    }

                    // Propagate scope to transitive dependencies for non-compile scopes
                    if (!"compile".equals(scope)) {
                        propagateScope(depNode, scope);
                    }

                    // Propagate managed version notes to children
                    propagateManagedVersionNotes(depNode, managedVersions);

                    root.addChild(depNode);
                } catch (Exception e) {
                    // Create a node for unresolvable dependency
                    DependencyNode errorNode = new DependencyNode(
                            dep.getGroupId(),
                            dep.getArtifactId(),
                            dep.getVersion() != null ? dep.getVersion() : "UNKNOWN"
                    );
                    errorNode.setScope(dep.getScope());
                    root.addChild(errorNode);
                }
            }

            return root;
        } catch (Exception e) {
            throw new Exception("Failed to parse POM file: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a DependencyNode tree from the Aether dependency graph.
     */
    private DependencyNode buildDependencyTree(org.eclipse.aether.graph.DependencyNode node) {
        org.eclipse.aether.graph.Dependency dep = node.getDependency();
        Artifact artifact = dep != null ? dep.getArtifact() : node.getArtifact();

        DependencyNode depNode = new DependencyNode(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion()
        );

        if (dep != null) {
            depNode.setScope(dep.getScope());
            depNode.setOptional(dep.isOptional());
        }

        // Notes from Maven Resolver data will be added later via propagateManagedVersionNotes

        // Recursively add children
        for (org.eclipse.aether.graph.DependencyNode child : node.getChildren()) {
            depNode.addChild(buildDependencyTree(child));
        }

        return depNode;
    }

    /**
     * Propagates scope to all transitive dependencies.
     * Used for test/provided/runtime scopes to show effective scope in the tree.
     * Maven scope rules: test and provided override all child scopes, runtime only overrides compile.
     */
    private void propagateScope(DependencyNode node, String scope) {
        node.setScope(scope);
        if (node.getChildren() != null) {
            for (DependencyNode child : node.getChildren()) {
                String childScope = child.getScope();

                // Test and provided scopes override everything
                if ("test".equals(scope) || "provided".equals(scope)) {
                    propagateScope(child, scope);
                }
                // Runtime scope only overrides compile
                else if ("runtime".equals(scope) && (childScope == null || "compile".equals(childScope))) {
                    propagateScope(child, scope);
                }
            }
        }
    }

    /**
     * Propagates managed version notes to transitive dependencies.
     */
    private void propagateManagedVersionNotes(DependencyNode node, java.util.Map<String, String> managedVersions) {
        if (node.getChildren() == null) {
            return;
        }

        for (DependencyNode child : node.getChildren()) {
            String key = child.getGroupId() + ":" + child.getArtifactId();
            String managedVersion = managedVersions.get(key);

            if (managedVersion != null && !managedVersion.equals(child.getVersion())) {
                // Add note about version management
                String note = "version managed from " + managedVersion;
                if (child.getNotes() != null) {
                    child.setNotes(child.getNotes() + "; " + note);
                } else {
                    child.setNotes(note);
                }
            }

            // Recursively process children
            propagateManagedVersionNotes(child, managedVersions);
        }
    }

    /**
     * Parses Maven dependency:tree output and builds DependencyNode tree.
     */
    private DependencyNode parseMavenTreeOutput(String output) throws Exception {
        String[] lines = output.split("\n");

        // Find the start of the dependency tree
        int startLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Look for the root dependency line (doesn't start with [INFO] prefix after the root)
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
                    node.setOmittedReason("omitted for " + matcher.group(1).trim());
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
     * Helper class for loading POM content from String.
     */
    private static class StringModelSource implements ModelSource {
        private final String pomContent;

        public StringModelSource(String pomContent) {
            this.pomContent = pomContent;
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(pomContent.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String getLocation() {
            return "StringModelSource";
        }
    }
}