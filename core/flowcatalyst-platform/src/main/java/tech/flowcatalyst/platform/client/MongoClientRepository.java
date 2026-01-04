package tech.flowcatalyst.platform.client;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.mongodb.client.model.Filters.*;

@ApplicationScoped
@Typed(ClientRepository.class)
class MongoClientRepository implements ClientRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<Client> collection() {
        return mongoClient.getDatabase(database).getCollection("clients", Client.class);
    }

    @Override
    public Client findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<Client> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<Client> findByIdentifier(String identifier) {
        return Optional.ofNullable(collection().find(eq("identifier", identifier)).first());
    }

    @Override
    public List<Client> findAllActive() {
        return collection().find(eq("status", ClientStatus.ACTIVE.name()))
            .into(new ArrayList<>());
    }

    @Override
    public List<Client> findByIds(Set<String> ids) {
        return collection().find(in("_id", ids)).into(new ArrayList<>());
    }

    @Override
    public List<Client> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public void persist(Client client) {
        collection().insertOne(client);
    }

    @Override
    public void update(Client client) {
        collection().replaceOne(eq("_id", client.id), client);
    }

    @Override
    public void delete(Client client) {
        collection().deleteOne(eq("_id", client.id));
    }
}
