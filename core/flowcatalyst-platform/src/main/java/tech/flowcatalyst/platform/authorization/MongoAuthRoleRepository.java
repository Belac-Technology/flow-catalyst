package tech.flowcatalyst.platform.authorization;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of AuthRoleRepository.
 * Package-private to prevent direct injection - use AuthRoleRepository interface.
 */
@ApplicationScoped
@Typed(AuthRoleRepository.class)
@Instrumented(collection = "auth_roles")
class MongoAuthRoleRepository implements PanacheMongoRepositoryBase<AuthRole, String>, AuthRoleRepository {

    @Override
    public Optional<AuthRole> findByName(String name) {
        return find("name", name).firstResultOptional();
    }

    @Override
    public List<AuthRole> findByApplicationId(String applicationId) {
        return find("applicationId", applicationId).list();
    }

    @Override
    public List<AuthRole> findByApplicationCode(String applicationCode) {
        return find("applicationCode", applicationCode).list();
    }

    @Override
    public List<AuthRole> findBySource(AuthRole.RoleSource source) {
        return find("source", source).list();
    }

    @Override
    public List<AuthRole> findClientManagedRoles() {
        return find("clientManaged", true).list();
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
    public long deleteByApplicationIdAndSource(String applicationId, AuthRole.RoleSource source) {
        return delete("applicationId = ?1 and source = ?2", applicationId, source);
    }

    // Delegate to Panache methods via interface
    @Override
    public AuthRole findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<AuthRole> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<AuthRole> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(AuthRole role) {
        PanacheMongoRepositoryBase.super.persist(role);
    }

    @Override
    public void update(AuthRole role) {
        PanacheMongoRepositoryBase.super.update(role);
    }

    @Override
    public void delete(AuthRole role) {
        PanacheMongoRepositoryBase.super.delete(role);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
