package tech.flowcatalyst.platform.authentication;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of IdpRoleMappingRepository.
 * Package-private to prevent direct injection - use IdpRoleMappingRepository interface.
 * SECURITY: Only explicitly authorized IDP roles should exist in this table.
 */
@ApplicationScoped
@Typed(IdpRoleMappingRepository.class)
@Instrumented(collection = "idp_role_mappings")
class MongoIdpRoleMappingRepository implements PanacheMongoRepositoryBase<IdpRoleMapping, String>, IdpRoleMappingRepository {

    @Override
    public Optional<IdpRoleMapping> findByIdpRoleName(String idpRoleName) {
        return find("idpRoleName", idpRoleName).firstResultOptional();
    }

    // Delegate to Panache methods via interface
    @Override
    public IdpRoleMapping findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<IdpRoleMapping> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<IdpRoleMapping> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(IdpRoleMapping mapping) {
        PanacheMongoRepositoryBase.super.persist(mapping);
    }

    @Override
    public void update(IdpRoleMapping mapping) {
        PanacheMongoRepositoryBase.super.update(mapping);
    }

    @Override
    public void delete(IdpRoleMapping mapping) {
        PanacheMongoRepositoryBase.super.delete(mapping);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
