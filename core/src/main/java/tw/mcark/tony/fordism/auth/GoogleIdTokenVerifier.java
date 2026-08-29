package tw.mcark.tony.fordism.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.tinylog.Logger;

/**
 * Checks a Google {@code id_token} end to end: RS256 signature against Google's published key,
 * then issuer, audience, expiry, nonce and a verified email.
 *
 * <p>Every one of those is load-bearing. Without the audience check a token Google minted for any
 * other application would sign somebody in here; without the nonce check a token captured from one
 * session could be replayed into another; without {@code email_verified} an attacker could claim a
 * colleague's address by putting it on an unverified Google account and letting the allowlist do
 * the rest.
 */
public final class GoogleIdTokenVerifier {

    /** Google mints both spellings and treats them as the same issuer. */
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private static final Gson GSON = new Gson();
    private static final String REQUIRED_ALGORITHM = "RS256";

    private final JsonWebKeys keys;
    private final String audience;

    public GoogleIdTokenVerifier(JsonWebKeys keys, String audience) {
        this.keys = keys;
        this.audience = audience;
    }

    /** The identity this token proves, or empty — with a logged reason — when it proves nothing. */
    public Optional<ExternalIdentity> verify(String idToken, String expectedNonce) {
        if (idToken == null || idToken.isBlank()) {
            return reject("no id_token in the token response");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            return reject("id_token is not a three-part JWS");
        }
        JsonObject header = decode(parts[0]);
        JsonObject claims = decode(parts[1]);
        if (header == null || claims == null) {
            return reject("id_token header or payload is not JSON");
        }
        if (!REQUIRED_ALGORITHM.equals(text(header, "alg"))) {
            // "none" and HMAC tokens are the classic forgeries; only RS256 is ever accepted.
            return reject("id_token alg is " + text(header, "alg") + ", not " + REQUIRED_ALGORITHM);
        }
        RSAPublicKey key = keys.find(text(header, "kid")).orElse(null);
        if (key == null) {
            return reject("Google publishes no key with id " + text(header, "kid"));
        }
        if (!signatureHolds(idToken, parts, key)) {
            return reject("id_token signature does not verify");
        }
        return checkedClaims(claims, expectedNonce);
    }

    private Optional<ExternalIdentity> checkedClaims(JsonObject claims, String expectedNonce) {
        if (!ISSUERS.contains(text(claims, "iss"))) {
            return reject("id_token iss is " + text(claims, "iss"));
        }
        if (!audience.equals(text(claims, "aud"))) {
            return reject("id_token aud is not this instance's client id");
        }
        if (number(claims, "exp") <= System.currentTimeMillis() / 1000) {
            return reject("id_token has expired");
        }
        if (expectedNonce != null && !expectedNonce.isBlank()
                && !expectedNonce.equals(text(claims, "nonce"))) {
            return reject("id_token nonce does not match this login attempt");
        }
        String email = text(claims, "email");
        if (email.isBlank() || !claims.has("email_verified") || !claims.get("email_verified").getAsBoolean()) {
            return reject("id_token carries no verified email");
        }
        String name = text(claims, "name");
        return Optional.of(new ExternalIdentity(AuthProviderId.GOOGLE, text(claims, "sub"), email,
                name.isBlank() ? email : name));
    }

    private static boolean signatureHolds(String idToken, String[] parts, RSAPublicKey key) {
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(key);
            rsa.update(idToken.substring(0, parts[0].length() + 1 + parts[1].length())
                    .getBytes(StandardCharsets.US_ASCII));
            return rsa.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            Logger.warn("id_token signature check failed: {}", e.getMessage());
            return false;
        }
    }

    private static JsonObject decode(String part) {
        try {
            return GSON.fromJson(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8),
                    JsonObject.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonPrimitive() ? json.get(field).getAsString() : "";
    }

    private static long number(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonPrimitive() ? json.get(field).getAsLong() : 0L;
    }

    private static Optional<ExternalIdentity> reject(String reason) {
        Logger.warn("rejected a Google id_token: {}", reason);
        return Optional.empty();
    }
}
