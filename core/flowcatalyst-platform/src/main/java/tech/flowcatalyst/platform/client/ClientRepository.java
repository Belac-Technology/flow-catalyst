package tech.flowcatalyst.platform.client;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository interface for Client entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface ClientRepository {

    // Read operations
    Client findById(String id);
    Optional<Client> findByIdOptional(String id);
    Optional<Client> findByIdentifier(String identifier);
    List<Client> findAllActive();
    List<Client> findByIds(Set<String> ids);
    List<Client> listAll();
    long count();

    // Write operations
    void persist(Client client);
    void update(Client client);
    void delete(Client client);
    boolean deleteById(String id);
}
