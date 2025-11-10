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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
     * Parses a pom.xml file and resolves its dependencies.
     */
    public DependencyNode resolveDependenciesFromPom(String pomContent) throws Exception {
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

            // Resolve each dependency
            for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
                try {
                    String scope = dep.getScope() != null ? dep.getScope() : "compile";
                    DependencyNode depNode = resolveDependencies(
                            dep.getGroupId(),
                            dep.getArtifactId(),
                            dep.getVersion(),
                            scope
                    );
                    depNode.setOptional("true".equals(dep.getOptional()));

                    // Propagate scope to transitive dependencies for non-compile scopes
                    if (!"compile".equals(scope)) {
                        propagateScope(depNode, scope);
                    }

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