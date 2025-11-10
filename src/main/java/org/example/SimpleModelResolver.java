package org.example;

import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import java.util.List;

/**
 * Simple implementation of ModelResolver for resolving parent POMs.
 */
public class SimpleModelResolver implements ModelResolver {

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    public SimpleModelResolver(RepositorySystem repositorySystem,
                               RepositorySystemSession session,
                               List<RemoteRepository> repositories) {
        this.repositorySystem = repositorySystem;
        this.session = session;
        this.repositories = repositories;
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        Artifact pomArtifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);
        return resolveArtifact(pomArtifact);
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        Artifact pomArtifact = new DefaultArtifact(
                parent.getGroupId(),
                parent.getArtifactId(),
                "",
                "pom",
                parent.getVersion()
        );
        return resolveArtifact(pomArtifact);
    }

    @Override
    public ModelSource resolveModel(org.apache.maven.model.Dependency dependency)
            throws UnresolvableModelException {
        Artifact pomArtifact = new DefaultArtifact(
                dependency.getGroupId(),
                dependency.getArtifactId(),
                "",
                "pom",
                dependency.getVersion()
        );
        return resolveArtifact(pomArtifact);
    }

    private ModelSource resolveArtifact(Artifact artifact) throws UnresolvableModelException {
        try {
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(artifact);
            request.setRepositories(repositories);

            ArtifactResult result = repositorySystem.resolveArtifact(session, request);
            return new FileModelSource(result.getArtifact().getFile());
        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException(
                    "Failed to resolve POM for " + artifact + ": " + e.getMessage(),
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getVersion(),
                    e
            );
        }
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        // For simplicity, we'll use the pre-configured repositories
    }

    @Override
    public void addRepository(Repository repository, boolean replace)
            throws InvalidRepositoryException {
        // For simplicity, we'll use the pre-configured repositories
    }

    @Override
    public ModelResolver newCopy() {
        return new SimpleModelResolver(repositorySystem, session, repositories);
    }
}