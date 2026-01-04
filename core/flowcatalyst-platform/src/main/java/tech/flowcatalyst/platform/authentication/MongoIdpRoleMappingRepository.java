package tech.flowcatalyst.platform.authentication;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of IdpRoleMappingRepository.
 * Package-private to prevent direct injection - use IdpRoleMappingRepository interface.
 * SECURITY: Only explicitly authorized IDP roles should exist in this table.
 */
@ApplicationScoped
@Typed(IdpRoleMappingRepository.class)
class MongoIdpRoleMappingRepository implements IdpRoleMappingRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<IdpRoleMapping> collection() {
        return mongoClient.getDatabase(database).getCollection("idp_role_mappings", IdpRoleMapping.class);
    }

    @Override
    public Optional<IdpRoleMapping> findByIdpRoleName(String idpRoleName) {
        return Optional.ofNullable(collection().find(eq("idpRoleName", idpRoleName)).first());
    }

    @Override
    public void persist(IdpRoleMapping mapping) {
        collection().insertOne(mapping);
    }

    @Override
    public void delete(IdpRoleMapping mapping) {
        collection().deleteOne(eq("_id", mapping.id));
    }
}
