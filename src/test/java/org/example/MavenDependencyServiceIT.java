package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for MavenDependencyService using the project's own pom.xml.
 */
class MavenDependencyServiceIT {

    private MavenDependencyService service;
    private String pomContent;

    @BeforeEach
    void setUp() throws IOException {
        service = new MavenDependencyService();

        // Read the pom.xml from the project root
        Path pomPath = Paths.get("pom.xml");
        assertTrue(Files.exists(pomPath), "pom.xml should exist in the project root");

        pomContent = Files.readString(pomPath);
        assertFalse(pomContent.isEmpty(), "pom.xml content should not be empty");
    }

    @Test
    void testResolveDependenciesFromPom() throws Exception {
        // Act
        DependencyNode root = service.resolveDependenciesFromPom(pomContent);

        // Assert - verify root project information
        assertNotNull(root, "Root dependency node should not be null");
        assertEquals("org.vaadin", root.getGroupId(), "Root groupId should match");
        assertEquals("vdt", root.getArtifactId(), "Root artifactId should match");
        assertEquals("1.0-SNAPSHOT", root.getVersion(), "Root version should match");

        // Assert - verify that dependencies were resolved
        List<DependencyNode> children = root.getChildren();
        assertNotNull(children, "Children list should not be null");
        assertFalse(children.isEmpty(), "Should have resolved at least one dependency");

        // Assert - verify specific expected dependencies from the pom.xml
        List<String> childCoordinatesPrefix = children.stream()
                .map(node -> node.getGroupId() + ":" + node.getArtifactId())
                .collect(Collectors.toList());

        assertTrue(
                childCoordinatesPrefix.contains("in.virit.ws:sb"),
                "Should contain in.virit.ws:sb dependency"
        );

        assertTrue(
                childCoordinatesPrefix.stream().anyMatch(coord -> coord.startsWith("in.virit:")),
                "Should contain at least one Viritin dependency"
        );

        assertTrue(
                childCoordinatesPrefix.stream().anyMatch(coord -> coord.startsWith("org.springframework.boot")),
                "Should contain Spring Boot test dependency"
        );

        // Assert - verify that at least one dependency has transitive dependencies
        boolean hasTransitiveDeps = children.stream()
                .anyMatch(child -> child.getChildren() != null && !child.getChildren().isEmpty());

        assertTrue(hasTransitiveDeps,
                "At least one dependency should have transitive dependencies resolved");

        // Print dependency tree for debugging (optional)
        System.out.println("Resolved dependency tree:");
        printDependencyTree(root, 0);
    }

    @Test
    void testSpecificDependencyDetails() throws Exception {
        // Act
        DependencyNode root = service.resolveDependenciesFromPom(pomContent);

        // Find the in.virit.ws:sb dependency
        DependencyNode sbtDep = root.getChildren().stream()
                .filter(node -> "in.virit.ws".equals(node.getGroupId())
                        && "sb".equals(node.getArtifactId()))
                .findFirst()
                .orElse(null);

        // Assert
        assertNotNull(sbtDep, "in.virit.ws:sb dependency should be found");
        assertNotNull(sbtDep.getVersion(), "sb dependency should have a version");
        assertFalse(sbtDep.getVersion().isEmpty(), "sb dependency version should not be empty");
    }

    @Test
    void testScopeInheritanceForTestDependencies() throws Exception {
        // Act
        DependencyNode root = service.resolveDependenciesFromPom(pomContent);

        // Find spring-boot-starter-test (test scoped dependency)
        DependencyNode testDep = root.getChildren().stream()
                .filter(node -> "org.springframework.boot".equals(node.getGroupId())
                        && "spring-boot-starter-test".equals(node.getArtifactId()))
                .findFirst()
                .orElse(null);

        assertNotNull(testDep, "spring-boot-starter-test should be found");
        assertEquals("test", testDep.getScope(), "spring-boot-starter-test should have test scope");

        // Test dependency found and has correct scope
        System.out.println("Test dependency found: " + testDep.getCoordinates() + " with scope " + testDep.getScope());
    }

    private DependencyNode findDependencyInTree(DependencyNode root, String groupId, String artifactId) {
        if (root == null || root.getChildren() == null) {
            return null;
        }

        for (DependencyNode child : root.getChildren()) {
            if (groupId.equals(child.getGroupId()) && artifactId.equals(child.getArtifactId())) {
                return child;
            }
            DependencyNode found = findDependencyInTree(child, groupId, artifactId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void testAllDependenciesHaveValidCoordinates() throws Exception {
        // Act
        DependencyNode root = service.resolveDependenciesFromPom(pomContent);

        // Assert - verify all dependencies have valid coordinates
        for (DependencyNode child : root.getChildren()) {
            assertNotNull(child.getGroupId(), "GroupId should not be null for " + child.getArtifactId());
            assertNotNull(child.getArtifactId(), "ArtifactId should not be null");
            assertNotNull(child.getVersion(), "Version should not be null for " + child.getArtifactId());
            assertFalse(child.getVersion().isEmpty(), "Version should not be empty for " + child.getArtifactId());
        }
    }

    /**
     * Helper method to print dependency tree for debugging.
     */
    private void printDependencyTree(DependencyNode node, int depth) {
        String indent = "  ".repeat(depth);
        System.out.println(indent + node.toString());

        if (node.getChildren() != null) {
            for (DependencyNode child : node.getChildren()) {
                printDependencyTree(child, depth + 1);
            }
        }
    }
}