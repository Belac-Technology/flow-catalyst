package tech.flowcatalyst.platform.authentication;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * Repository for IdpRoleMapping entities.
 * SECURITY: Only explicitly authorized IDP roles should exist in this table.
 */
@ApplicationScoped
public class IdpRoleMappingRepository implements PanacheMongoRepositoryBase<IdpRoleMapping, Long> {

    public Optional<IdpRoleMapping> findByIdpRoleName(String idpRoleName) {
        return find("idpRoleName", idpRoleName).firstResultOptional();
    }
}
