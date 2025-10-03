package tech.flowcatalyst.platform.principal;

import jakarta.enterprise.context.ApplicationScoped;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.WildFlyElytronPasswordProvider;
import org.wildfly.security.password.interfaces.BCryptPassword;
import org.wildfly.security.password.spec.EncryptablePasswordSpec;
import org.wildfly.security.password.spec.IteratedSaltedPasswordAlgorithmSpec;
import org.wildfly.security.password.util.ModularCrypt;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.InvalidKeySpecException;

/**
 * Service for password hashing and validation using BCrypt via WildFly Elytron.
 * Quarkus includes Elytron which provides BCrypt support.
 */
@ApplicationScoped
public class PasswordService {

    private static final String BCRYPT_ALGORITHM = BCryptPassword.ALGORITHM_BCRYPT;
    private static final int BCRYPT_COST = 10; // BCrypt cost parameter (iterations = 2^10)
    private static final int SALT_SIZE = 16; // 16 bytes = 128 bits

    static {
        // Register WildFly Elytron password provider with Java Security
        Security.addProvider(WildFlyElytronPasswordProvider.getInstance());
    }

    /**
     * Hash a password using BCrypt.
     *
     * @param plainPassword The plain text password
     * @return The hashed password in Modular Crypt Format
     */
    public String hashPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        try {
            PasswordFactory factory = PasswordFactory.getInstance(BCRYPT_ALGORITHM);

            // Generate random salt
            byte[] salt = new byte[SALT_SIZE];
            new SecureRandom().nextBytes(salt);

            // Create algorithm spec with cost and salt
            IteratedSaltedPasswordAlgorithmSpec spec =
                new IteratedSaltedPasswordAlgorithmSpec(BCRYPT_COST, salt);

            // Generate password
            EncryptablePasswordSpec encryptSpec =
                new EncryptablePasswordSpec(plainPassword.toCharArray(), spec);

            Password password = factory.generatePassword(encryptSpec);

            // Return in Modular Crypt Format (MCF)
            return ModularCrypt.encodeAsString(password);

        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    /**
     * Verify a password against a hash.
     *
     * @param plainPassword The plain text password to verify
     * @param passwordHash The hash to verify against
     * @return true if password matches the hash
     */
    public boolean verifyPassword(String plainPassword, String passwordHash) {
        if (plainPassword == null || passwordHash == null) {
            return false;
        }

        try {
            PasswordFactory factory = PasswordFactory.getInstance(BCRYPT_ALGORITHM);

            // Decode the stored password hash
            Password userPassword = ModularCrypt.decode(passwordHash);

            // Translate to BCryptPassword
            Password inputPassword = factory.translate(userPassword);

            // Verify the password
            return factory.verify(inputPassword, plainPassword.toCharArray());

        } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException e) {
            // Invalid hash format or algorithm error
            return false;
        }
    }

    /**
     * Validate password complexity requirements.
     *
     * @param password The password to validate
     * @throws IllegalArgumentException if password doesn't meet requirements
     */
    public void validatePasswordComplexity(String password) {
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("Password must be at least 12 characters long");
        }

        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars().anyMatch(ch ->
            "!@#$%^&*()_+-=[]{}|;:,.<>?".indexOf(ch) >= 0
        );

        if (!hasUpper) {
            throw new IllegalArgumentException("Password must contain at least one uppercase letter");
        }

        if (!hasLower) {
            throw new IllegalArgumentException("Password must contain at least one lowercase letter");
        }

        if (!hasDigit) {
            throw new IllegalArgumentException("Password must contain at least one digit");
        }

        if (!hasSpecial) {
            throw new IllegalArgumentException("Password must contain at least one special character (!@#$%^&*()_+-=[]{}|;:,.<>?)");
        }
    }

    /**
     * Validate and hash a password.
     *
     * @param plainPassword The password to validate and hash
     * @return The hashed password
     * @throws IllegalArgumentException if password doesn't meet complexity requirements
     */
    public String validateAndHashPassword(String plainPassword) {
        validatePasswordComplexity(plainPassword);
        return hashPassword(plainPassword);
    }
}
