package tech.flowcatalyst.auth.model;

/**
 * Permission definition (stored as JSON in Role).
 * Represents a specific action on a resource.
 */
public class Permission {

    public String resource;
    public String action;
    public String description;

    public Permission() {
    }

    public Permission(String resource, String action, String description) {
        this.resource = resource;
        this.action = action;
        this.description = description;
    }
}
