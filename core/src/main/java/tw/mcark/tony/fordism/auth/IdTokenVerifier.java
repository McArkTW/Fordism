package tw.mcark.tony.fordism.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Optional;
import org.tinylog.Logger;

/**
 * Checks an OpenID Connect {@code id_token} end to end: RS256 signature against the issuer's
 * published key, then issuer, audience, expiry and nonce.
 *
 * <p>Every one of those is load-bearing. Without the audience check a token minted for any other
 * application would sign somebody in here; without the nonce check a token captured from one
 * session could be replayed into another; without the algorithm check the classic "alg: none" and
 * HMAC forgeries walk straight through.
 *
 * <p>What it deliberately does NOT do is decide who the person is. It answers "this token is
 * genuine and it is for us", and hands back the claims; the provider that asked turns those into an
 * {@link ExternalIdentity} under its own rule for what makes an address trustworthy. That rule is
 * not shared — see {@link VerifiedClaims} — and pretending it was is how one issuer's policy ends
 * up silently applied to another's tokens.
 *
 * <p>This was {@code GoogleIdTokenVerifier}. Nothing about the checks changed when Microsoft
 * sign-in arrived; only the issuer, the audience and the key source became arguments.
 */
public final class IdTokenVerifier {

    private static final Gson GSON = new Gson();
    private static final String REQUIRED_ALGORITHM = "RS256";

    private final JsonWebKeys keys;
    private final IdTokenExpectation expectation;

    public IdTokenVerifier(JsonWebKeys keys, IdTokenExpectation expectation) {
        this.keys = keys;
        this.expectation = expectation;
    }

    /** The claims this token proves, or empty — with a logged reason — when it proves nothing. */
    public Optional<VerifiedClaims> verify(String idToken, String expectedNonce) {
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
            return reject("the issuer publishes no key with id " + text(header, "kid"));
        }
        if (!signatureHolds(idToken, parts, key)) {
            return reject("id_token signature does not verify");
        }
        return checkedClaims(claims, expectedNonce);
    }

    private Optional<VerifiedClaims> checkedClaims(JsonObject claims, String expectedNonce) {
        if (!expectation.issuedBy(text(claims, "iss"))) {
            return reject("id_token iss is " + text(claims, "iss"));
        }
        if (!expectation.audience().equals(text(claims, "aud"))) {
            return reject("id_token aud is not this instance's client id");
        }
        if (number(claims, "exp") <= System.currentTimeMillis() / 1000) {
            return reject("id_token has expired");
        }
        if (expectedNonce != null && !expectedNonce.isBlank()
                && !expectedNonce.equals(text(claims, "nonce"))) {
            return reject("id_token nonce does not match this login attempt");
        }
        return Optional.of(new VerifiedClaims(text(claims, "sub"), text(claims, "email"),
                claims.has("email_verified") && claims.get("email_verified").getAsBoolean(),
                text(claims, "preferred_username"), text(claims, "name")));
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

    private static Optional<VerifiedClaims> reject(String reason) {
        Logger.warn("rejected an id_token: {}", reason);
        return Optional.empty();
    }
}
