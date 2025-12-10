package tech.flowcatalyst.platform.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalType;
import tech.flowcatalyst.platform.principal.AnchorDomainRepository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for calculating which clients a principal can access.
 *
 * Access rules:
 * 1. Anchor domain users -> ALL clients (global access)
 * 2. Home client (principal.clientId) if exists
 * 3. Explicitly granted clients (ClientAccessGrant)
 */
@ApplicationScoped
public class ClientAccessService {

    @Inject
    ClientRepository clientRepo;

    @Inject
    AnchorDomainRepository anchorDomainRepo;

    @Inject
    ClientAccessGrantRepository grantRepo;

    /**
     * Calculate which clients a principal can access.
     *
     * @param principal The principal (user or service account)
     * @return Set of accessible client IDs
     */
    public Set<String> getAccessibleClients(Principal principal) {
        Set<String> clientIds = new HashSet<>();

        // 1. Check if anchor domain user (global access)
        if (principal.type == PrincipalType.USER && principal.userIdentity != null) {
            String domain = principal.userIdentity.emailDomain;
            if (domain != null && anchorDomainRepo.existsByDomain(domain)) {
                // Return ALL active client IDs
                return clientRepo.findAllActive().stream()
                    .map(c -> c.id)
                    .collect(Collectors.toSet());
            }
        }

        // 2. Add home client if exists and is active
        if (principal.clientId != null) {
            clientIds.add(principal.clientId);
        }

        // 3. Add explicitly granted clients (filter expired grants)
        List<ClientAccessGrant> grants = grantRepo.findByPrincipalId(principal.id);
        Instant now = Instant.now();

        grants.stream()
            .filter(g -> g.expiresAt == null || g.expiresAt.isAfter(now))
            .map(g -> g.clientId)
            .forEach(clientIds::add);

        // 4. Filter out inactive/suspended clients
        if (!clientIds.isEmpty()) {
            List<Client> clients = clientRepo.findByIds(clientIds);
            return clients.stream()
                .filter(c -> c.status == ClientStatus.ACTIVE)
                .map(c -> c.id)
                .collect(Collectors.toSet());
        }

        return clientIds;
    }

    /**
     * Check if principal can access a specific client.
     *
     * @param principal The principal
     * @param clientId The client ID to check
     * @return true if principal can access the client
     */
    public boolean canAccessClient(Principal principal, String clientId) {
        return getAccessibleClients(principal).contains(clientId);
    }

    /**
     * Check if principal is an anchor domain user (has global access).
     *
     * @param principal The principal
     * @return true if anchor domain user
     */
    public boolean isAnchorDomainUser(Principal principal) {
        if (principal.type != PrincipalType.USER || principal.userIdentity == null) {
            return false;
        }

        String domain = principal.userIdentity.emailDomain;
        return domain != null && anchorDomainRepo.existsByDomain(domain);
    }
}
