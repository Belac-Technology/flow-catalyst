package tech.flowcatalyst.platform.authorization;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of AuthPermissionRepository.
 * Package-private to prevent direct injection - use AuthPermissionRepository interface.
 */
@ApplicationScoped
@Typed(AuthPermissionRepository.class)
@Instrumented(collection = "auth_permissions")
class MongoAuthPermissionRepository implements PanacheMongoRepositoryBase<AuthPermission, String>, AuthPermissionRepository {

    @Override
    public Optional<AuthPermission> findByName(String name) {
        return find("name", name).firstResultOptional();
    }

    @Override
    public List<AuthPermission> findByApplicationId(String applicationId) {
        return find("applicationId", applicationId).list();
    }

    @Override
    public List<AuthPermission> findByApplicationCode(String applicationCode) {
        return find("applicationCode", applicationCode).list();
    }

    @Override
    public boolean existsByName(String name) {
        return count("name", name) > 0;
    }

    @Override
    public long deleteByName(String name) {
        return delete("name", name);
    }

    @Override
    public long deleteByApplicationId(String applicationId) {
        return delete("applicationId", applicationId);
    }

    // Delegate to Panache methods via interface
    @Override
    public AuthPermission findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<AuthPermission> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<AuthPermission> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(AuthPermission permission) {
        PanacheMongoRepositoryBase.super.persist(permission);
    }

    @Override
    public void update(AuthPermission permission) {
        PanacheMongoRepositoryBase.super.update(permission);
    }

    @Override
    public void delete(AuthPermission permission) {
        PanacheMongoRepositoryBase.super.delete(permission);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
