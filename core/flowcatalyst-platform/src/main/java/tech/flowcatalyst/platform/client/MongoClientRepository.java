package tech.flowcatalyst.platform.client;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MongoDB implementation of ClientRepository.
 * Package-private to prevent direct injection - use ClientRepository interface.
 */
@ApplicationScoped
@Typed(ClientRepository.class)
@Instrumented(collection = "clients")
class MongoClientRepository implements PanacheMongoRepositoryBase<Client, String>, ClientRepository {

    @Override
    public Optional<Client> findByIdentifier(String identifier) {
        return find("identifier", identifier).firstResultOptional();
    }

    @Override
    public List<Client> findAllActive() {
        return find("status", ClientStatus.ACTIVE).list();
    }

    @Override
    public List<Client> findByIds(Set<String> ids) {
        return find("id in ?1", ids).list();
    }

    // Delegate to Panache methods via interface
    @Override
    public Client findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<Client> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<Client> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(Client client) {
        PanacheMongoRepositoryBase.super.persist(client);
    }

    @Override
    public void update(Client client) {
        PanacheMongoRepositoryBase.super.update(client);
    }

    @Override
    public void delete(Client client) {
        PanacheMongoRepositoryBase.super.delete(client);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
