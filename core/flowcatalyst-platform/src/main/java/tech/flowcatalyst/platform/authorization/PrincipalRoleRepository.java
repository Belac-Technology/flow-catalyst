package tech.flowcatalyst.platform.authorization;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.platform.authorization.PrincipalRole;

import java.util.List;

/**
 * Repository for PrincipalRole junction entities.
 */
@ApplicationScoped
public class PrincipalRoleRepository implements PanacheRepositoryBase<PrincipalRole, Long> {

    public List<PrincipalRole> findByPrincipalId(Long principalId) {
        return find("principalId", principalId).list();
    }

    public void deleteByPrincipalIdAndAssignmentSource(Long principalId, String assignmentSource) {
        delete("principalId = ?1 and assignmentSource = ?2", principalId, assignmentSource);
    }
}
