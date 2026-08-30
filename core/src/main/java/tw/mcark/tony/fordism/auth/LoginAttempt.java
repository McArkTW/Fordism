package tw.mcark.tony.fordism.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * One redirect to a provider, remembered until the browser comes back.
 *
 * <p>{@code state} is what proves the callback belongs to a redirect this server started;
 * {@code codeVerifier} is the PKCE secret that stops an intercepted authorization code being
 * redeemed by anyone else; {@code nonce} is echoed inside Google's id_token, which is how a replayed
 * token from another session is spotted.
 */
public record LoginAttempt(AuthProviderId provider, String state, String codeVerifier, String nonce,
                           long startedAt) {

    /** An attempt is only good for the few seconds a human takes to click "allow". */
    public static final long LIFETIME_MILLIS = 10 * 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static LoginAttempt starting(AuthProviderId provider) {
        return new LoginAttempt(provider, random(), random(), random(), System.currentTimeMillis());
    }

    public boolean isLiveAt(long now) {
        return now - startedAt < LIFETIME_MILLIS;
    }

    /** The S256 PKCE challenge derived from this attempt's verifier. */
    public String codeChallenge() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no SHA-256 — PKCE cannot be used", e);
        }
    }

    private static String random() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
