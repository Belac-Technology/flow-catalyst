package tech.flowcatalyst.platform.principal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import tech.flowcatalyst.platform.authentication.IdpType;
import java.time.Instant;

/**
 * User identity information (embedded in Principal for USER type).
 */
@Embeddable
public class UserIdentity {

    @Column(name = "user_email", unique = true, length = 255)
    public String email;

    @Column(name = "user_email_domain", length = 255)
    public String emailDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_idp_type", length = 20)
    public IdpType idpType;

    @Column(name = "user_external_idp_id", length = 255)
    public String externalIdpId; // Subject from OIDC token

    @Column(name = "user_password_hash", length = 255)
    public String passwordHash; // For INTERNAL auth only (Argon2id)

    @Column(name = "user_last_login_at")
    public Instant lastLoginAt;

    public UserIdentity() {
    }
}
