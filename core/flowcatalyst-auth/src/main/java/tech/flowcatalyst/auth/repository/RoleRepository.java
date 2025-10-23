package tech.flowcatalyst.auth.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.auth.model.Role;

import java.util.Optional;

/**
 * Repository for Role entities.
 */
@ApplicationScoped
public class RoleRepository implements PanacheRepositoryBase<Role, Long> {

    public Optional<Role> findByName(String name) {
        return find("name", name).firstResultOptional();
    }
}
