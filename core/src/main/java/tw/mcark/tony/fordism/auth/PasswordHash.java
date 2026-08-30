package tw.mcark.tony.fordism.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * A stored password, as one self-describing string:
 * {@code pbkdf2-sha256$<iterations>$<salt>$<key>}, both halves Base64.
 *
 * <p>Self-describing because the cost parameter will move. A hash written today must still verify
 * after {@link #ITERATIONS} is raised, and it can only do that if the number it was written with
 * travels with it — a bare digest would silently stop verifying every existing password the day
 * someone edited the constant.
 *
 * <p>PBKDF2-HMAC-SHA256 from the JDK, a per-user random salt, and a constant-time compare. No new
 * dependency: argon2id would be a better function, but it is not in the platform, and a well-iterated
 * PBKDF2 is a far smaller risk than an unvetted third-party jar in the login path.
 */
public record PasswordHash(String encoded) {

    /** OWASP's floor for PBKDF2-HMAC-SHA256. Raise it freely — old hashes keep verifying. */
    public static final int ITERATIONS = 210_000;

    private static final String LABEL = "pbkdf2-sha256";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Hash a new password with a fresh salt. */
    public static PasswordHash of(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("a password is required");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, new Derivation(salt, ITERATIONS, KEY_BITS));
        return new PasswordHash(LABEL + "$" + ITERATIONS + "$" + encode(salt) + "$" + encode(key));
    }

    /** Whether this candidate is the password this hash was made from. */
    public boolean matches(String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !LABEL.equals(parts[0])) {
            throw new IllegalStateException("stored password hash is not " + LABEL + ": " + encoded);
        }
        byte[] salt = decode(parts[2], "salt");
        byte[] expected = decode(parts[3], "key");
        byte[] actual = derive(password, new Derivation(salt, iterations(parts[1]), expected.length * 8));
        // Constant-time: a length-independent early exit would leak how much of a guess was right.
        return MessageDigest.isEqual(expected, actual);
    }

    /** The cost parameters one derivation runs with — kept together so derive() stays a two-argument call. */
    private record Derivation(byte[] salt, int iterations, int keyBits) {}

    private static byte[] derive(String password, Derivation derivation) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), derivation.salt(),
                    derivation.iterations(), derivation.keyBits());
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no " + ALGORITHM + " — passwords cannot be hashed", e);
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("could not derive a key with " + ALGORITHM, e);
        }
    }

    private static int iterations(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("stored password hash has an unreadable iteration count: " + value, e);
        }
    }

    private static String encode(byte[] raw) {
        return Base64.getEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] decode(String value, String what) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("stored password hash has an unreadable " + what, e);
        }
    }
}
