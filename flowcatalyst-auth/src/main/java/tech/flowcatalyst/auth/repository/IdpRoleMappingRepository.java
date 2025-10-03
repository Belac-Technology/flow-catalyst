package tech.flowcatalyst.auth.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.auth.model.IdpRoleMapping;

import java.util.Optional;

/**
 * Repository for IdpRoleMapping entities.
 * SECURITY: Only explicitly authorized IDP roles should exist in this table.
 */
@ApplicationScoped
public class IdpRoleMappingRepository implements PanacheRepositoryBase<IdpRoleMapping, Long> {

    public Optional<IdpRoleMapping> findByIdpRoleName(String idpRoleName) {
        return find("idpRoleName", idpRoleName).firstResultOptional();
    }
}
