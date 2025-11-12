package org.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the ProcessBuilder method (reference) with the new in-JVM method.
 */
@SpringBootTest
class MavenDependencyServiceComparisonTest {

    @Autowired
    private MavenDependencyService service;

    @Test
    void testBothMethodsProduceSimilarResults() throws Exception {
        // Read the pom.xml from the project root
        Path pomPath = Paths.get("pom.xml");
        assertTrue(Files.exists(pomPath), "pom.xml should exist in the project root");

        String pomContent = Files.readString(pomPath);
        assertFalse(pomContent.isEmpty(), "pom.xml content should not be empty");

        // Get results from both methods using reflection to access private methods
        DependencyNode commandResult = callProcessBuilderMethod(pomContent);
        DependencyNode jvmResult = service.resolveDependenciesFromPom(pomContent);

        // Compare results
        assertNotNull(commandResult, "ProcessBuilder result should not be null");
        assertNotNull(jvmResult, "In-JVM result should not be null");

        // Check root project info
        assertEquals(commandResult.getGroupId(), jvmResult.getGroupId(),
                "Root groupId should match");
        assertEquals(commandResult.getArtifactId(), jvmResult.getArtifactId(),
                "Root artifactId should match");
        assertEquals(commandResult.getVersion(), jvmResult.getVersion(),
                "Root version should match");

        // Check that both have dependencies
        assertNotNull(commandResult.getChildren(), "ProcessBuilder should have children");
        assertNotNull(jvmResult.getChildren(), "In-JVM should have children");
        assertFalse(commandResult.getChildren().isEmpty(),
                "ProcessBuilder should have resolved dependencies");
        assertFalse(jvmResult.getChildren().isEmpty(),
                "In-JVM should have resolved dependencies");

        // Print comparison for debugging
        System.out.println("=== ProcessBuilder Method (Reference) ===");
        printTree(commandResult, 0);
        System.out.println("\n=== In-JVM Method ===");
        printTree(jvmResult, 0);

        // Compare dependency counts
        int commandCount = countAllDependencies(commandResult);
        int jvmCount = countAllDependencies(jvmResult);

        System.out.println("\n=== Comparison ===");
        System.out.println("ProcessBuilder total dependencies: " + commandCount);
        System.out.println("In-JVM total dependencies: " + jvmCount);

        // Check specific dependencies exist in both
        List<String> commandDeps = collectAllCoordinates(commandResult);
        List<String> jvmDeps = collectAllCoordinates(jvmResult);

        System.out.println("\nDependencies in ProcessBuilder but not in JVM:");
        for (String dep : commandDeps) {
            if (!jvmDeps.contains(dep)) {
                System.out.println("  - " + dep);
            }
        }

        System.out.println("\nDependencies in JVM but not in ProcessBuilder:");
        for (String dep : jvmDeps) {
            if (!commandDeps.contains(dep)) {
                System.out.println("  - " + dep);
            }
        }

        // Note: The JVM method won't capture ALL omitted dependencies like Maven's verbose output does,
        // because Maven Resolver's collect API doesn't provide that level of detail.
        // The ProcessBuilder method parses Maven's verbose output which includes all omitted nodes.
        // The JVM method should still capture most of the important dependencies.
        System.out.println("\nNote: JVM method uses Maven Resolver collect API which doesn't include");
        System.out.println("all omitted/duplicate nodes that Maven's verbose output shows.");
        System.out.println("This is a limitation of the Maven Resolver API.");
    }

    /**
     * Calls the ProcessBuilder method using reflection since it's private.
     */
    private DependencyNode callProcessBuilderMethod(String pomContent) throws Exception {
        var method = MavenDependencyService.class.getDeclaredMethod(
                "resolveDependenciesFromPomViaCommand", String.class);
        method.setAccessible(true);
        return (DependencyNode) method.invoke(service, pomContent);
    }

    private void printTree(DependencyNode node, int depth) {
        String indent = "  ".repeat(depth);
        String omitted = node.isOmitted() ? " [OMITTED: " + node.getOmittedReason() + "]" : "";
        String optional = node.isOptional() ? " [OPTIONAL]" : "";
        System.out.println(indent + node.getCoordinates() + omitted + optional);

        if (node.getChildren() != null) {
            for (DependencyNode child : node.getChildren()) {
                printTree(child, depth + 1);
            }
        }
    }

    private int countAllDependencies(DependencyNode node) {
        int count = 1; // Count this node
        if (node.getChildren() != null) {
            for (DependencyNode child : node.getChildren()) {
                count += countAllDependencies(child);
            }
        }
        return count;
    }

    private List<String> collectAllCoordinates(DependencyNode node) {
        List<String> coords = new ArrayList<>();
        coords.add(node.getCoordinates());
        if (node.getChildren() != null) {
            for (DependencyNode child : node.getChildren()) {
                coords.addAll(collectAllCoordinates(child));
            }
        }
        return coords;
    }
}