package tech.flowcatalyst.platform.client;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for Client entities.
 */
@ApplicationScoped
public class ClientRepository implements PanacheRepositoryBase<Client, Long> {

    public Optional<Client> findByIdentifier(String identifier) {
        return find("identifier", identifier).firstResultOptional();
    }

    public List<Client> findAllActive() {
        return find("status", ClientStatus.ACTIVE).list();
    }

    public List<Client> findByIds(Set<Long> ids) {
        return find("id in ?1", ids).list();
    }
}
