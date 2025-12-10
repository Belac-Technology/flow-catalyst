package tech.flowcatalyst.platform.authorization;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Repository for PrincipalRole junction entities.
 */
@ApplicationScoped
public class PrincipalRoleRepository implements PanacheMongoRepositoryBase<PrincipalRole, String> {

    public List<PrincipalRole> findByPrincipalId(String principalId) {
        return find("principalId", principalId).list();
    }

    public void deleteByPrincipalIdAndAssignmentSource(String principalId, String assignmentSource) {
        delete("principalId = ?1 and assignmentSource = ?2", principalId, assignmentSource);
    }
}
