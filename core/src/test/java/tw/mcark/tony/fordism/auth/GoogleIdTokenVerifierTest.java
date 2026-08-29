package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * id_token verification, against a key this test generated — no network, and no dependency on
 * Google being reachable from a build machine.
 *
 * <p>Each rejection here is a real attack: an unsigned or HMAC-signed token is the classic JWT
 * forgery, a token minted for another application is what an attacker gets by registering their
 * own OAuth client, and an unverified email is how somebody claims a colleague's address.
 */
class GoogleIdTokenVerifierTest {
    private static final String AUDIENCE = "fordism-client-id.apps.googleusercontent.com";
    private static final String KEY_ID = "test-key";
    private static final String NONCE = "the-nonce-we-sent";

    private static KeyPair keyPair;
    private static GoogleIdTokenVerifier verifier;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        RSAPublicKey published = (RSAPublicKey) keyPair.getPublic();
        JsonWebKeys keys = keyId -> KEY_ID.equals(keyId) ? Optional.of(published) : Optional.empty();
        verifier = new GoogleIdTokenVerifier(keys, AUDIENCE);
    }

    @Test
    void a_well_formed_token_yields_the_identity_it_carries() {
        Optional<ExternalIdentity> identity = verifier.verify(token(header(KEY_ID, "RS256"), claims()), NONCE);
        assertTrue(identity.isPresent());
        assertEquals(AuthProviderId.GOOGLE, identity.orElseThrow().provider());
        assertEquals("109876543210", identity.orElseThrow().subject());
        assertEquals("dana@example.com", identity.orElseThrow().email());
        assertEquals("Dana Scully", identity.orElseThrow().displayName());
    }

    @Test
    void the_short_issuer_spelling_google_also_mints_is_accepted() {
        JsonObject claims = claims();
        claims.addProperty("iss", "accounts.google.com");
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).isPresent());
    }

    @Test
    void a_token_minted_for_another_application_is_refused() {
        JsonObject claims = claims();
        claims.addProperty("aud", "somebody-elses-client-id.apps.googleusercontent.com");
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).isEmpty());
    }

    @Test
    void an_expired_token_is_refused() {
        JsonObject claims = claims();
        claims.addProperty("exp", System.currentTimeMillis() / 1000 - 60);
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).isEmpty());
    }

    @Test
    void a_token_from_another_login_attempt_is_refused() {
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims()), "a-different-nonce").isEmpty());
    }

    @Test
    void an_unverified_email_is_refused_because_the_allowlist_would_trust_it() {
        JsonObject claims = claims();
        claims.addProperty("email_verified", false);
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).isEmpty());
    }

    @Test
    void an_algorithm_other_than_rs256_is_refused_however_the_header_asks() {
        assertTrue(verifier.verify(token(header(KEY_ID, "none"), claims()), NONCE).isEmpty());
        assertTrue(verifier.verify(token(header(KEY_ID, "HS256"), claims()), NONCE).isEmpty());
    }

    @Test
    void a_key_google_does_not_publish_is_refused() {
        assertTrue(verifier.verify(token(header("some-other-key", "RS256"), claims()), NONCE).isEmpty());
    }

    @Test
    void a_tampered_payload_breaks_the_signature() {
        String[] parts = token(header(KEY_ID, "RS256"), claims()).split("\\.");
        JsonObject elevated = claims();
        elevated.addProperty("email", "admin@example.com");
        String forged = parts[0] + "." + encode(elevated.toString()) + "." + parts[2];
        assertTrue(verifier.verify(forged, NONCE).isEmpty());
    }

    @Test
    void nonsense_is_refused_rather_than_thrown() {
        assertTrue(verifier.verify(null, NONCE).isEmpty());
        assertTrue(verifier.verify("", NONCE).isEmpty());
        assertTrue(verifier.verify("not.a.jwt", NONCE).isEmpty());
        assertTrue(verifier.verify("only-one-part", NONCE).isEmpty());
        assertFalse(verifier.verify("a.b", NONCE).isPresent());
    }

    private static JsonObject header(String keyId, String algorithm) {
        JsonObject header = new JsonObject();
        header.addProperty("alg", algorithm);
        header.addProperty("kid", keyId);
        header.addProperty("typ", "JWT");
        return header;
    }

    private static JsonObject claims() {
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", "https://accounts.google.com");
        claims.addProperty("aud", AUDIENCE);
        claims.addProperty("sub", "109876543210");
        claims.addProperty("exp", System.currentTimeMillis() / 1000 + 600);
        claims.addProperty("nonce", NONCE);
        claims.addProperty("email", "dana@example.com");
        claims.addProperty("email_verified", true);
        claims.addProperty("name", "Dana Scully");
        return claims;
    }

    /** Signs whatever it is given — including headers Google would never write. */
    private static String token(JsonObject header, JsonObject claims) {
        String signed = encode(header.toString()) + "." + encode(claims.toString());
        return signed + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature(signed, keyPair.getPrivate()));
    }

    private static byte[] signature(String signed, PrivateKey key) {
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(key);
            rsa.update(signed.getBytes(StandardCharsets.US_ASCII));
            return rsa.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("could not sign the test token", e);
        }
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
