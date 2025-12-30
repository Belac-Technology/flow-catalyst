package tech.flowcatalyst.platform.principal;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for AnchorDomain entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface AnchorDomainRepository {

    // Read operations
    AnchorDomain findById(String id);
    Optional<AnchorDomain> findByIdOptional(String id);
    Optional<AnchorDomain> findByDomain(String domain);
    List<AnchorDomain> listAll();
    long count();
    boolean existsByDomain(String domain);
    boolean isAnchorDomain(String domain);

    // Write operations
    void persist(AnchorDomain domain);
    void update(AnchorDomain domain);
    void delete(AnchorDomain domain);
    boolean deleteById(String id);
}
