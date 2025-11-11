package org.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a node in the dependency tree.
 */
public class DependencyNode {
    private String groupId;
    private String artifactId;
    private String version;
    private String scope;
    private String type;
    private boolean optional;
    private String notes;
    private boolean omitted;
    private String omittedReason;
    private List<DependencyNode> children = new ArrayList<>();
    private DependencyNode parent;

    public DependencyNode(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public DependencyNode(String groupId, String artifactId, String version, String scope, String type, boolean optional) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.scope = scope;
        this.type = type;
        this.optional = optional;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isOptional() {
        return optional;
    }

    public void setOptional(boolean optional) {
        this.optional = optional;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isOmitted() {
        return omitted;
    }

    public void setOmitted(boolean omitted) {
        this.omitted = omitted;
    }

    public String getOmittedReason() {
        return omittedReason;
    }

    public void setOmittedReason(String omittedReason) {
        this.omittedReason = omittedReason;
    }

    public List<DependencyNode> getChildren() {
        return children;
    }

    public void setChildren(List<DependencyNode> children) {
        this.children = children;
    }

    public void addChild(DependencyNode child) {
        child.setParent(this);
        this.children.add(child);
    }

    public DependencyNode getParent() {
        return parent;
    }

    public void setParent(DependencyNode parent) {
        this.parent = parent;
    }

    public String getCoordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId).append(":").append(artifactId).append(":").append(version);
        if (type != null && !"jar".equals(type)) {
            sb.append(":").append(type);
        }
        if (scope != null && !"compile".equals(scope)) {
            sb.append(" (").append(scope).append(")");
        }
        if (optional) {
            sb.append(" (optional)");
        }
        return sb.toString();
    }
}