package tech.flowcatalyst.platform.authentication.oidc;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class OidcLoginStateRepository implements PanacheMongoRepositoryBase<OidcLoginState, String> {

    public Optional<OidcLoginState> findValidState(String state) {
        return find("_id = ?1 and expiresAt > ?2", state, Instant.now()).firstResultOptional();
    }

    public void deleteExpired() {
        delete("expiresAt < ?1", Instant.now());
    }

    public void deleteByState(String state) {
        deleteById(state);
    }
}
