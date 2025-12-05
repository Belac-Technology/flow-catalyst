package tech.flowcatalyst.platform.service;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.flowcatalyst.platform.authentication.IdpType;
import tech.flowcatalyst.platform.principal.PasswordService;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.principal.PrincipalType;
import tech.flowcatalyst.platform.principal.UserIdentity;
import tech.flowcatalyst.platform.principal.UserService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserService.
 * Tests user CRUD operations for both INTERNAL and OIDC users.
 * Uses mocked repositories and password service.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private PrincipalRepository principalRepo;

    @Mock
    private PasswordService passwordService;

    @InjectMocks
    private UserService service;

    // ========================================
    // createInternalUser TESTS
    // ========================================

    @Test
    @DisplayName("createInternalUser should create user when all parameters valid")
    void createInternalUser_shouldCreateUser_whenAllParametersValid() {
        // Arrange
        String email = "john@acme.com";
        String password = "SecurePass123!";
        String name = "John Doe";
        Long clientId = 100L;

        when(principalRepo.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordService.validateAndHashPassword(password)).thenReturn("$2a$10$hashed...");

        // Act
        Principal result = service.createInternalUser(email, password, name, clientId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.id).isNotNull(); // TSID generated
        assertThat(result.type).isEqualTo(PrincipalType.USER);
        assertThat(result.clientId).isEqualTo(clientId);
        assertThat(result.name).isEqualTo(name);
        assertThat(result.active).isTrue();

        assertThat(result.userIdentity).isNotNull();
        assertThat(result.userIdentity.email).isEqualTo(email);
        assertThat(result.userIdentity.emailDomain).isEqualTo("acme.com");
        assertThat(result.userIdentity.idpType).isEqualTo(IdpType.INTERNAL);
        assertThat(result.userIdentity.passwordHash).isEqualTo("$2a$10$hashed...");

        verify(principalRepo).persist(result);
    }

    @Test
    @DisplayName("createInternalUser should throw exception when email is null")
    void createInternalUser_shouldThrowException_whenEmailIsNull() {
        assertThatThrownBy(() -> service.createInternalUser(null, "Password123!", "John", 100L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Email cannot be null or empty");
    }

    @Test
    @DisplayName("createInternalUser should throw exception when email is blank")
    void createInternalUser_shouldThrowException_whenEmailIsBlank() {
        assertThatThrownBy(() -> service.createInternalUser("  ", "Password123!", "John", 100L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Email cannot be null or empty");
    }

    @Test
    @DisplayName("createInternalUser should throw exception when email already exists")
    void createInternalUser_shouldThrowException_whenEmailAlreadyExists() {
        // Arrange
        String email = "existing@acme.com";
        Principal existingPrincipal = createInternalUserPrincipal(1L, email, 100L);

        when(principalRepo.findByEmail(email)).thenReturn(Optional.of(existingPrincipal));

        // Act & Assert
        assertThatThrownBy(() -> service.createInternalUser(email, "Password123!", "John", 100L))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Email already exists");

        verify(passwordService, never()).validateAndHashPassword(anyString());
        verify(principalRepo, never()).persist(any(Principal.class));
    }

    @Test
    @DisplayName("createInternalUser should throw exception when password invalid")
    void createInternalUser_shouldThrowException_whenPasswordInvalid() {
        // Arrange
        String email = "john@acme.com";
        String weakPassword = "weak";

        when(principalRepo.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordService.validateAndHashPassword(weakPassword))
            .thenThrow(new IllegalArgumentException("Password must be at least 12 characters long"));

        // Act & Assert
        assertThatThrownBy(() -> service.createInternalUser(email, weakPassword, "John", 100L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Password must be at least 12 characters long");

        verify(principalRepo, never()).persist(any(Principal.class));
    }

    @Test
    @DisplayName("createInternalUser should accept null clientId for anchor domain users")
    void createInternalUser_shouldAcceptNullClientId_whenAnchorDomainUser() {
        // Arrange
        String email = "admin@mycompany.com";
        when(principalRepo.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordService.validateAndHashPassword(anyString())).thenReturn("$2a$10$hashed...");

        // Act
        Principal result = service.createInternalUser(email, "SecurePass123!", "Admin", null);

        // Assert
        assertThat(result.clientId).isNull();
        assertThat(result.userIdentity.emailDomain).isEqualTo("mycompany.com");
    }

    @Test
    @DisplayName("createInternalUser should extract domain correctly from email")
    void createInternalUser_shouldExtractDomain_whenEmailValid() {
        // Arrange
        when(principalRepo.findByEmail(anyString())).thenReturn(Optional.empty());
        when(passwordService.validateAndHashPassword(anyString())).thenReturn("$2a$10$hashed...");

        // Act
        Principal result = service.createInternalUser("USER@ACME.COM", "SecurePass123!", "User", 100L);

        // Assert: Domain should be lowercase
        assertThat(result.userIdentity.emailDomain).isEqualTo("acme.com");
    }

    // ========================================
    // createOrUpdateOidcUser TESTS
    // ========================================

    @Test
    @DisplayName("createOrUpdateOidcUser should create new user when user does not exist")
    void createOrUpdateOidcUser_shouldCreateNewUser_whenUserDoesNotExist() {
        // Arrange
        String email = "alice@customer.com";
        String name = "Alice Smith";
        String externalIdpId = "google-oauth2|123456789";
        Long clientId = 200L;

        when(principalRepo.findByEmail(email)).thenReturn(Optional.empty());

        // Act
        Instant beforeCall = Instant.now();
        Principal result = service.createOrUpdateOidcUser(email, name, externalIdpId, clientId);
        Instant afterCall = Instant.now();

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.id).isNotNull();
        assertThat(result.type).isEqualTo(PrincipalType.USER);
        assertThat(result.clientId).isEqualTo(clientId);
        assertThat(result.name).isEqualTo(name);
        assertThat(result.active).isTrue();

        assertThat(result.userIdentity).isNotNull();
        assertThat(result.userIdentity.email).isEqualTo(email);
        assertThat(result.userIdentity.emailDomain).isEqualTo("customer.com");
        assertThat(result.userIdentity.idpType).isEqualTo(IdpType.OIDC);
        assertThat(result.userIdentity.externalIdpId).isEqualTo(externalIdpId);
        assertThat(result.userIdentity.passwordHash).isNull();
        assertThat(result.userIdentity.lastLoginAt).isBetween(beforeCall, afterCall);

        verify(principalRepo).persist(result);
    }

    @Test
    @DisplayName("createOrUpdateOidcUser should update existing user when user already exists")
    void createOrUpdateOidcUser_shouldUpdateExistingUser_whenUserAlreadyExists() {
        // Arrange
        String email = "alice@customer.com";
        String oldName = "Alice Old";
        String newName = "Alice Updated";
        String oldExternalId = "old-id-123";
        String newExternalId = "new-id-456";

        Principal existingUser = createOidcUserPrincipal(1L, email, 200L, oldExternalId);
        existingUser.name = oldName;

        when(principalRepo.findByEmail(email)).thenReturn(Optional.of(existingUser));

        // Act
        Instant beforeCall = Instant.now();
        Principal result = service.createOrUpdateOidcUser(email, newName, newExternalId, 200L);
        Instant afterCall = Instant.now();

        // Assert
        assertThat(result.id).isEqualTo(1L); // Same ID
        assertThat(result.name).isEqualTo(newName); // Updated name
        assertThat(result.userIdentity.externalIdpId).isEqualTo(newExternalId); // Updated external ID
        assertThat(result.userIdentity.lastLoginAt).isBetween(beforeCall, afterCall);

        verify(principalRepo, never()).persist(any(Principal.class)); // No new persistence, existing entity updated
    }

    @Test
    @DisplayName("createOrUpdateOidcUser should accept null clientId for anchor domain")
    void createOrUpdateOidcUser_shouldAcceptNullClientId_whenAnchorDomain() {
        // Arrange
        when(principalRepo.findByEmail(anyString())).thenReturn(Optional.empty());

        // Act
        Principal result = service.createOrUpdateOidcUser("admin@mycompany.com", "Admin", "idp-123", null);

        // Assert
        assertThat(result.clientId).isNull();
    }

    // ========================================
    // updateUser TESTS
    // ========================================

    @Test
    @DisplayName("updateUser should update name when user exists")
    void updateUser_shouldUpdateName_whenUserExists() {
        // Arrange
        Long principalId = 123L;
        Principal user = createInternalUserPrincipal(principalId, "john@acme.com", 100L);
        user.name = "Old Name";

        when(principalRepo.findByIdOptional(principalId)).thenReturn(Optional.of(user));

        // Act
        Principal result = service.updateUser(principalId, "New Name");

        // Assert
        assertThat(result.name).isEqualTo("New Name");
    }

    @Test
    @DisplayName("updateUser should throw NotFoundException when user does not exist")
    void updateUser_shouldThrowNotFoundException_whenUserDoesNotExist() {
        // Arrange
        when(principalRepo.findByIdOptional(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.updateUser(999L, "New Name"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("updateUser should throw exception when principal is not a user")
    void updateUser_shouldThrowException_whenPrincipalIsNotUser() {
        // Arrange
        Principal serviceAccount = new Principal();
        serviceAccount.id = 123L;
        serviceAccount.type = PrincipalType.SERVICE;

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(serviceAccount));

        // Act & Assert
        assertThatThrownBy(() -> service.updateUser(123L, "New Name"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Principal is not a user");
    }

    // ========================================
    // deactivateUser TESTS
    // ========================================

    @Test
    @DisplayName("deactivateUser should set active to false when user exists")
    void deactivateUser_shouldSetActiveFalse_whenUserExists() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        user.active = true;

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));

        // Act
        service.deactivateUser(123L);

        // Assert
        assertThat(user.active).isFalse();
    }

    @Test
    @DisplayName("deactivateUser should throw NotFoundException when user does not exist")
    void deactivateUser_shouldThrowNotFoundException_whenUserDoesNotExist() {
        // Arrange
        when(principalRepo.findByIdOptional(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.deactivateUser(999L))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("deactivateUser should throw exception when principal is service account")
    void deactivateUser_shouldThrowException_whenPrincipalIsServiceAccount() {
        // Arrange
        Principal serviceAccount = new Principal();
        serviceAccount.id = 123L;
        serviceAccount.type = PrincipalType.SERVICE;

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(serviceAccount));

        // Act & Assert
        assertThatThrownBy(() -> service.deactivateUser(123L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Principal is not a user");
    }

    // ========================================
    // activateUser TESTS
    // ========================================

    @Test
    @DisplayName("activateUser should set active to true when user exists")
    void activateUser_shouldSetActiveTrue_whenUserExists() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        user.active = false;

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));

        // Act
        service.activateUser(123L);

        // Assert
        assertThat(user.active).isTrue();
    }

    @Test
    @DisplayName("activateUser should throw NotFoundException when user does not exist")
    void activateUser_shouldThrowNotFoundException_whenUserDoesNotExist() {
        // Arrange
        when(principalRepo.findByIdOptional(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.activateUser(999L))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("User not found");
    }

    // ========================================
    // resetPassword TESTS
    // ========================================

    @Test
    @DisplayName("resetPassword should update password hash when user is INTERNAL")
    void resetPassword_shouldUpdatePasswordHash_whenUserIsInternal() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        user.userIdentity.passwordHash = "$2a$10$oldHash";

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));
        when(passwordService.validateAndHashPassword("NewSecurePass123!"))
            .thenReturn("$2a$10$newHash");

        // Act
        service.resetPassword(123L, "NewSecurePass123!");

        // Assert
        assertThat(user.userIdentity.passwordHash).isEqualTo("$2a$10$newHash");
    }

    @Test
    @DisplayName("resetPassword should throw exception when user is OIDC")
    void resetPassword_shouldThrowException_whenUserIsOidc() {
        // Arrange
        Principal oidcUser = createOidcUserPrincipal(123L, "alice@customer.com", 200L, "idp-123");

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(oidcUser));

        // Act & Assert
        assertThatThrownBy(() -> service.resetPassword(123L, "NewSecurePass123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cannot reset password for OIDC users");

        verify(passwordService, never()).validateAndHashPassword(anyString());
    }

    @Test
    @DisplayName("resetPassword should throw NotFoundException when user does not exist")
    void resetPassword_shouldThrowNotFoundException_whenUserDoesNotExist() {
        // Arrange
        when(principalRepo.findByIdOptional(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.resetPassword(999L, "NewSecurePass123!"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("resetPassword should validate password complexity")
    void resetPassword_shouldValidatePasswordComplexity_whenResettingPassword() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));
        when(passwordService.validateAndHashPassword("weak"))
            .thenThrow(new IllegalArgumentException("Password must be at least 12 characters long"));

        // Act & Assert
        assertThatThrownBy(() -> service.resetPassword(123L, "weak"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Password must be at least 12 characters long");
    }

    // ========================================
    // changePassword TESTS
    // ========================================

    @Test
    @DisplayName("changePassword should update password when old password correct")
    void changePassword_shouldUpdatePassword_whenOldPasswordCorrect() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        user.userIdentity.passwordHash = "$2a$10$oldHash";

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));
        when(passwordService.verifyPassword("OldSecurePass123!", "$2a$10$oldHash")).thenReturn(true);
        when(passwordService.validateAndHashPassword("NewSecurePass123!"))
            .thenReturn("$2a$10$newHash");

        // Act
        service.changePassword(123L, "OldSecurePass123!", "NewSecurePass123!");

        // Assert
        assertThat(user.userIdentity.passwordHash).isEqualTo("$2a$10$newHash");
    }

    @Test
    @DisplayName("changePassword should throw exception when old password incorrect")
    void changePassword_shouldThrowException_whenOldPasswordIncorrect() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        user.userIdentity.passwordHash = "$2a$10$oldHash";

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));
        when(passwordService.verifyPassword("WrongOldPass!", "$2a$10$oldHash")).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> service.changePassword(123L, "WrongOldPass!", "NewSecurePass123!"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Current password is incorrect");

        // Password should not be changed
        assertThat(user.userIdentity.passwordHash).isEqualTo("$2a$10$oldHash");
        verify(passwordService, never()).validateAndHashPassword(anyString());
    }

    @Test
    @DisplayName("changePassword should throw exception when user is OIDC")
    void changePassword_shouldThrowException_whenUserIsOidc() {
        // Arrange
        Principal oidcUser = createOidcUserPrincipal(123L, "alice@customer.com", 200L, "idp-123");

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(oidcUser));

        // Act & Assert
        assertThatThrownBy(() -> service.changePassword(123L, "OldPass123!", "NewPass123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cannot change password for OIDC users");
    }

    @Test
    @DisplayName("changePassword should validate new password complexity")
    void changePassword_shouldValidateNewPasswordComplexity_whenChangingPassword() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        user.userIdentity.passwordHash = "$2a$10$oldHash";

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));
        when(passwordService.verifyPassword("OldSecurePass123!", "$2a$10$oldHash")).thenReturn(true);
        when(passwordService.validateAndHashPassword("weak"))
            .thenThrow(new IllegalArgumentException("Password must be at least 12 characters long"));

        // Act & Assert
        assertThatThrownBy(() -> service.changePassword(123L, "OldSecurePass123!", "weak"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Password must be at least 12 characters long");
    }

    // ========================================
    // findByEmail TESTS
    // ========================================

    @Test
    @DisplayName("findByEmail should return user when email exists")
    void findByEmail_shouldReturnUser_whenEmailExists() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        when(principalRepo.findByEmail("john@acme.com")).thenReturn(Optional.of(user));

        // Act
        Optional<Principal> result = service.findByEmail("john@acme.com");

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().id).isEqualTo(123L);
        assertThat(result.get().userIdentity.email).isEqualTo("john@acme.com");
    }

    @Test
    @DisplayName("findByEmail should return empty when email does not exist")
    void findByEmail_shouldReturnEmpty_whenEmailDoesNotExist() {
        // Arrange
        when(principalRepo.findByEmail("nonexistent@acme.com")).thenReturn(Optional.empty());

        // Act
        Optional<Principal> result = service.findByEmail("nonexistent@acme.com");

        // Assert
        assertThat(result).isEmpty();
    }

    // ========================================
    // findById TESTS
    // ========================================

    @Test
    @DisplayName("findById should return user when ID exists")
    void findById_shouldReturnUser_whenIdExists() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));

        // Act
        Optional<Principal> result = service.findById(123L);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().id).isEqualTo(123L);
    }

    @Test
    @DisplayName("findById should return empty when ID does not exist")
    void findById_shouldReturnEmpty_whenIdDoesNotExist() {
        // Arrange
        when(principalRepo.findByIdOptional(999L)).thenReturn(Optional.empty());

        // Act
        Optional<Principal> result = service.findById(999L);

        // Assert
        assertThat(result).isEmpty();
    }

    // ========================================
    // findByClient TESTS
    // ========================================

    @Test
    @DisplayName("findByClient should return all users for client including inactive")
    void findByClient_shouldReturnAllUsers_whenClientHasUsers() {
        // Arrange
        Principal user1 = createInternalUserPrincipal(1L, "john@acme.com", 100L);
        user1.active = true;
        Principal user2 = createInternalUserPrincipal(2L, "jane@acme.com", 100L);
        user2.active = false; // Inactive user

        io.quarkus.hibernate.orm.panache.PanacheQuery<Principal> mockQuery = mockPanacheQuery(List.of(user1, user2));
        when(principalRepo.find("clientId = ?1 AND type = ?2", 100L, PrincipalType.USER))
            .thenReturn(mockQuery);

        // Act
        List<Principal> result = service.findByClient(100L);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder(user1, user2);
    }

    @Test
    @DisplayName("findByClient should return empty list when client has no users")
    void findByClient_shouldReturnEmpty_whenClientHasNoUsers() {
        // Arrange
        io.quarkus.hibernate.orm.panache.PanacheQuery<Principal> mockQuery = mockPanacheQuery(List.of());
        when(principalRepo.find("clientId = ?1 AND type = ?2", 999L, PrincipalType.USER))
            .thenReturn(mockQuery);

        // Act
        List<Principal> result = service.findByClient(999L);

        // Assert
        assertThat(result).isEmpty();
    }

    // ========================================
    // findActiveByClient TESTS
    // ========================================

    @Test
    @DisplayName("findActiveByClient should return only active users")
    void findActiveByClient_shouldReturnOnlyActiveUsers_whenClientHasUsers() {
        // Arrange
        Principal user1 = createInternalUserPrincipal(1L, "john@acme.com", 100L);
        user1.active = true;

        io.quarkus.hibernate.orm.panache.PanacheQuery<Principal> mockQuery = mockPanacheQuery(List.of(user1));
        when(principalRepo.find("clientId = ?1 AND type = ?2 AND active = true", 100L, PrincipalType.USER))
            .thenReturn(mockQuery);

        // Act
        List<Principal> result = service.findActiveByClient(100L);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).active).isTrue();
    }

    // ========================================
    // updateLastLogin TESTS
    // ========================================

    @Test
    @DisplayName("updateLastLogin should update timestamp when user exists")
    void updateLastLogin_shouldUpdateTimestamp_whenUserExists() {
        // Arrange
        Principal user = createInternalUserPrincipal(123L, "john@acme.com", 100L);
        user.userIdentity.lastLoginAt = null;

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(user));

        // Act
        Instant beforeCall = Instant.now();
        service.updateLastLogin(123L);
        Instant afterCall = Instant.now();

        // Assert
        assertThat(user.userIdentity.lastLoginAt).isNotNull();
        assertThat(user.userIdentity.lastLoginAt).isBetween(beforeCall, afterCall);
    }

    @Test
    @DisplayName("updateLastLogin should not crash when user does not exist")
    void updateLastLogin_shouldNotCrash_whenUserDoesNotExist() {
        // Arrange
        when(principalRepo.findByIdOptional(999L)).thenReturn(Optional.empty());

        // Act & Assert: Should not throw
        assertThatCode(() -> service.updateLastLogin(999L))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("updateLastLogin should handle null userIdentity gracefully")
    void updateLastLogin_shouldHandleNullUserIdentity_whenUserIdentityIsNull() {
        // Arrange
        Principal serviceAccount = new Principal();
        serviceAccount.id = 123L;
        serviceAccount.type = PrincipalType.SERVICE;
        serviceAccount.userIdentity = null;

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(serviceAccount));

        // Act & Assert: Should not throw
        assertThatCode(() -> service.updateLastLogin(123L))
            .doesNotThrowAnyException();
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private Principal createInternalUserPrincipal(Long id, String email, Long clientId) {
        Principal p = new Principal();
        p.id = id;
        p.type = PrincipalType.USER;
        p.clientId = clientId;
        p.name = "Test User";
        p.active = true;

        UserIdentity identity = new UserIdentity();
        identity.email = email;
        identity.emailDomain = extractDomain(email);
        identity.idpType = IdpType.INTERNAL;
        identity.passwordHash = "$2a$10$dummyHash";

        p.userIdentity = identity;
        return p;
    }

    private Principal createOidcUserPrincipal(Long id, String email, Long clientId, String externalIdpId) {
        Principal p = new Principal();
        p.id = id;
        p.type = PrincipalType.USER;
        p.clientId = clientId;
        p.name = "Test OIDC User";
        p.active = true;

        UserIdentity identity = new UserIdentity();
        identity.email = email;
        identity.emailDomain = extractDomain(email);
        identity.idpType = IdpType.OIDC;
        identity.externalIdpId = externalIdpId;
        identity.passwordHash = null;

        p.userIdentity = identity;
        return p;
    }

    private String extractDomain(String email) {
        int atIndex = email.indexOf('@');
        return email.substring(atIndex + 1).toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private <T> io.quarkus.hibernate.orm.panache.PanacheQuery<T> mockPanacheQuery(List<T> results) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<T> mockQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(mockQuery.list()).thenReturn(results);
        return mockQuery;
    }
}
