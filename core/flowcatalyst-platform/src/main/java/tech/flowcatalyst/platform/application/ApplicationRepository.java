package tech.flowcatalyst.platform.application;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Application entities.
 */
@ApplicationScoped
public class ApplicationRepository implements PanacheMongoRepositoryBase<Application, Long> {

    public Optional<Application> findByCode(String code) {
        return find("code", code).firstResultOptional();
    }

    public List<Application> findAllActive() {
        return list("active = true");
    }

    public List<Application> findByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return list("code in ?1 and active = true", codes);
    }

    public boolean existsByCode(String code) {
        return count("code", code) > 0;
    }
}
