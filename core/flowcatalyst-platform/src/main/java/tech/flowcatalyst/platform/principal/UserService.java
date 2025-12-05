package tech.flowcatalyst.platform.principal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.flowcatalyst.platform.authentication.IdpType;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for user CRUD operations.
 * Handles both INTERNAL (password-based) and OIDC (external IDP) users.
 */
@ApplicationScoped
public class UserService {

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    PasswordService passwordService;

    /**
     * Create a new user with INTERNAL authentication (password-based).
     *
     * @param email User email address
     * @param password Plain text password (will be hashed)
     * @param name Display name
     * @param clientId Home client ID (nullable for anchor domain users)
     * @return Created principal
     * @throws IllegalArgumentException if password doesn't meet complexity requirements
     * @throws BadRequestException if email already exists
     */
    @Transactional
    public Principal createInternalUser(String email, String password, String name, Long clientId) {
        // Validate email
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email cannot be null or empty");
        }

        // Check if email already exists
        if (principalRepo.findByEmail(email).isPresent()) {
            throw new BadRequestException("Email already exists: " + email);
        }

        // Validate and hash password
        String passwordHash = passwordService.validateAndHashPassword(password);

        // Extract domain from email
        String emailDomain = extractDomain(email);

        // Create principal
        Principal principal = new Principal();
        principal.id = TsidGenerator.generate();
        principal.type = PrincipalType.USER;
        principal.clientId = clientId;
        principal.name = name;
        principal.active = true;

        // Create user identity
        UserIdentity userIdentity = new UserIdentity();
        userIdentity.email = email;
        userIdentity.emailDomain = emailDomain;
        userIdentity.idpType = IdpType.INTERNAL;
        userIdentity.passwordHash = passwordHash;

        principal.userIdentity = userIdentity;

        principalRepo.persist(principal);
        return principal;
    }

    /**
     * Create or update OIDC user (during OIDC login flow).
     * This is called by OidcSyncService during OIDC authentication.
     *
     * @param email User email from OIDC token
     * @param name Display name from OIDC token
     * @param externalIdpId Subject from OIDC token (IDP's user ID)
     * @param clientId Home client ID (nullable for anchor domain users)
     * @return Created or updated principal
     */
    @Transactional
    public Principal createOrUpdateOidcUser(String email, String name, String externalIdpId, Long clientId) {
        String emailDomain = extractDomain(email);

        // Check if user already exists
        Optional<Principal> existing = principalRepo.findByEmail(email);
        if (existing.isPresent()) {
            Principal principal = existing.get();
            // Update name and external IDP ID if changed
            principal.name = name;
            principal.userIdentity.externalIdpId = externalIdpId;
            principal.userIdentity.lastLoginAt = Instant.now();
            return principal;
        }

        // Create new OIDC user
        Principal principal = new Principal();
        principal.id = TsidGenerator.generate();
        principal.type = PrincipalType.USER;
        principal.clientId = clientId;
        principal.name = name;
        principal.active = true;

        UserIdentity userIdentity = new UserIdentity();
        userIdentity.email = email;
        userIdentity.emailDomain = emailDomain;
        userIdentity.idpType = IdpType.OIDC;
        userIdentity.externalIdpId = externalIdpId;
        userIdentity.lastLoginAt = Instant.now();

        principal.userIdentity = userIdentity;

        principalRepo.persist(principal);
        return principal;
    }

    /**
     * Update user profile (name).
     *
     * @param principalId Principal ID
     * @param name New display name
     * @return Updated principal
     * @throws NotFoundException if user not found
     */
    @Transactional
    public Principal updateUser(Long principalId, String name) {
        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("User not found"));

        if (principal.type != PrincipalType.USER) {
            throw new IllegalArgumentException("Principal is not a user");
        }

        principal.name = name;
        return principal;
    }

    /**
     * Deactivate a user (soft delete).
     * Deactivated users cannot log in.
     *
     * @param principalId Principal ID
     * @throws NotFoundException if user not found
     */
    @Transactional
    public void deactivateUser(Long principalId) {
        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("User not found"));

        if (principal.type != PrincipalType.USER) {
            throw new IllegalArgumentException("Principal is not a user");
        }

        principal.active = false;
    }

    /**
     * Activate a previously deactivated user.
     *
     * @param principalId Principal ID
     * @throws NotFoundException if user not found
     */
    @Transactional
    public void activateUser(Long principalId) {
        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("User not found"));

        if (principal.type != PrincipalType.USER) {
            throw new IllegalArgumentException("Principal is not a user");
        }

        principal.active = true;
    }

    /**
     * Reset password for INTERNAL auth user.
     * Only works for users with INTERNAL auth.
     *
     * @param principalId Principal ID
     * @param newPassword New plain text password
     * @throws NotFoundException if user not found
     * @throws IllegalArgumentException if user is not INTERNAL auth
     */
    @Transactional
    public void resetPassword(Long principalId, String newPassword) {
        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("User not found"));

        if (principal.type != PrincipalType.USER) {
            throw new IllegalArgumentException("Principal is not a user");
        }

        if (principal.userIdentity.idpType != IdpType.INTERNAL) {
            throw new IllegalArgumentException("Cannot reset password for OIDC users");
        }

        // Validate and hash new password
        String passwordHash = passwordService.validateAndHashPassword(newPassword);
        principal.userIdentity.passwordHash = passwordHash;
    }

    /**
     * Change password for INTERNAL auth user.
     * Validates the old password before changing.
     *
     * @param principalId Principal ID
     * @param oldPassword Old password (for verification)
     * @param newPassword New password
     * @throws NotFoundException if user not found
     * @throws BadRequestException if old password is incorrect
     */
    @Transactional
    public void changePassword(Long principalId, String oldPassword, String newPassword) {
        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("User not found"));

        if (principal.type != PrincipalType.USER) {
            throw new IllegalArgumentException("Principal is not a user");
        }

        if (principal.userIdentity.idpType != IdpType.INTERNAL) {
            throw new IllegalArgumentException("Cannot change password for OIDC users");
        }

        // Verify old password
        if (!passwordService.verifyPassword(oldPassword, principal.userIdentity.passwordHash)) {
            throw new BadRequestException("Current password is incorrect");
        }

        // Validate and hash new password
        String passwordHash = passwordService.validateAndHashPassword(newPassword);
        principal.userIdentity.passwordHash = passwordHash;
    }

    /**
     * Find user by email.
     *
     * @param email Email address
     * @return Optional containing the principal if found
     */
    public Optional<Principal> findByEmail(String email) {
        return principalRepo.findByEmail(email);
    }

    /**
     * Find user by principal ID.
     *
     * @param principalId Principal ID
     * @return Optional containing the principal if found
     */
    public Optional<Principal> findById(Long principalId) {
        return principalRepo.findByIdOptional(principalId);
    }

    /**
     * Find all users belonging to a specific client.
     *
     * @param clientId Client ID
     * @return List of principals
     */
    public List<Principal> findByClient(Long clientId) {
        return principalRepo.find("clientId = ?1 AND type = ?2", clientId, PrincipalType.USER).list();
    }

    /**
     * Find all active users belonging to a specific client.
     *
     * @param clientId Client ID
     * @return List of active principals
     */
    public List<Principal> findActiveByClient(Long clientId) {
        return principalRepo.find("clientId = ?1 AND type = ?2 AND active = true",
            clientId, PrincipalType.USER).list();
    }

    /**
     * Update last login timestamp.
     * Called during successful authentication.
     *
     * @param principalId Principal ID
     */
    @Transactional
    public void updateLastLogin(Long principalId) {
        Principal principal = principalRepo.findByIdOptional(principalId).orElse(null);
        if (principal != null && principal.userIdentity != null) {
            principal.userIdentity.lastLoginAt = Instant.now();
        }
    }

    /**
     * Extract domain from email address.
     *
     * @param email Email address
     * @return Domain part (e.g., "acmecorp.com")
     */
    private String extractDomain(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex < 0) {
            throw new IllegalArgumentException("Invalid email format");
        }
        return email.substring(atIndex + 1).toLowerCase();
    }
}
