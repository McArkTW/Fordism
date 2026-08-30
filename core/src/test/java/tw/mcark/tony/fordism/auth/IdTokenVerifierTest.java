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
 * id_token verification, against a key this test generated — no network, and no dependency on any
 * issuer being reachable from a build machine.
 *
 * <p>Each rejection here is a real attack: an unsigned or HMAC-signed token is the classic JWT
 * forgery, a token minted for another application is what an attacker gets by registering their own
 * OAuth client, and a token from a different issuer is what they get by standing one up.
 *
 * <p>What makes an ADDRESS trustworthy is not tested here, because the verifier does not decide it
 * — see {@code GoogleOAuth.identityFrom} and {@code MicrosoftOAuth.identityFrom}, and the cases
 * below that pin each of those rules through this same signed-token machinery.
 */
class IdTokenVerifierTest {
    private static final String AUDIENCE = "fordism-client-id.apps.googleusercontent.com";
    private static final String KEY_ID = "test-key";
    private static final String NONCE = "the-nonce-we-sent";
    private static final String TENANT = "11111111-2222-3333-4444-555555555555";

    private static KeyPair keyPair;
    private static IdTokenVerifier verifier;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        RSAPublicKey published = (RSAPublicKey) keyPair.getPublic();
        JsonWebKeys keys = keyId -> KEY_ID.equals(keyId) ? Optional.of(published) : Optional.empty();
        verifier = new IdTokenVerifier(keys, IdTokenExpectation.google(AUDIENCE));
    }

    @Test
    void a_well_formed_token_yields_the_claims_it_carries() {
        Optional<VerifiedClaims> claims = verifier.verify(token(header(KEY_ID, "RS256"), googleClaims()), NONCE);
        assertTrue(claims.isPresent());
        assertEquals("109876543210", claims.orElseThrow().subject());
        assertEquals("dana@example.com", claims.orElseThrow().email());
        assertTrue(claims.orElseThrow().emailVerified());
        assertEquals("Dana Scully", claims.orElseThrow().displayName());
    }

    @Test
    void the_short_issuer_spelling_google_also_mints_is_accepted() {
        JsonObject claims = googleClaims();
        claims.addProperty("iss", "accounts.google.com");
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).isPresent());
    }

    @Test
    void a_token_from_an_issuer_this_provider_does_not_expect_is_refused() {
        JsonObject claims = googleClaims();
        claims.addProperty("iss", "https://login.microsoftonline.com/" + TENANT + "/v2.0");
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).isEmpty(),
                "an issuer set that leaked across providers would accept either one's tokens");
    }

    @Test
    void a_token_minted_for_another_application_is_refused() {
        JsonObject claims = googleClaims();
        claims.addProperty("aud", "somebody-elses-client-id.apps.googleusercontent.com");
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).isEmpty());
    }

    @Test
    void an_expired_token_is_refused() {
        JsonObject claims = googleClaims();
        claims.addProperty("exp", System.currentTimeMillis() / 1000 - 60);
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).isEmpty());
    }

    @Test
    void a_token_from_another_login_attempt_is_refused() {
        assertTrue(verifier.verify(token(header(KEY_ID, "RS256"), googleClaims()), "a-different-nonce").isEmpty());
    }

    @Test
    void an_algorithm_other_than_rs256_is_refused_however_the_header_asks() {
        assertTrue(verifier.verify(token(header(KEY_ID, "none"), googleClaims()), NONCE).isEmpty());
        assertTrue(verifier.verify(token(header(KEY_ID, "HS256"), googleClaims()), NONCE).isEmpty());
    }

    @Test
    void a_key_the_issuer_does_not_publish_is_refused() {
        assertTrue(verifier.verify(token(header("some-other-key", "RS256"), googleClaims()), NONCE).isEmpty());
    }

    @Test
    void a_tampered_payload_breaks_the_signature() {
        String[] parts = token(header(KEY_ID, "RS256"), googleClaims()).split("\\.");
        JsonObject elevated = googleClaims();
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

    // ---- what each provider makes of verified claims ----

    @Test
    void an_unverified_google_email_carries_through_the_verifier_and_is_refused_by_the_provider() {
        // The verifier's job ends at "this token is genuine": email_verified is Google's rule, so
        // the claim survives verification and the provider is what refuses it. Without that refusal
        // somebody could claim a colleague's address on an unverified Google account and let the
        // allowlist do the rest.
        JsonObject claims = googleClaims();
        claims.addProperty("email_verified", false);
        VerifiedClaims verified = verifier.verify(token(header(KEY_ID, "RS256"), claims), NONCE).orElseThrow();
        assertFalse(verified.emailVerified());
        assertTrue(GoogleOAuth.identityFrom(verified).isEmpty(),
                "Google sign-in must refuse an unverified email");
    }

    @Test
    void a_verified_google_token_becomes_the_identity_it_carries() {
        VerifiedClaims verified = verifier.verify(token(header(KEY_ID, "RS256"), googleClaims()), NONCE)
                .orElseThrow();
        ExternalIdentity identity = GoogleOAuth.identityFrom(verified).orElseThrow();
        assertEquals(AuthProviderId.GOOGLE, identity.provider());
        assertEquals("109876543210", identity.subject());
        assertEquals("dana@example.com", identity.email());
        assertEquals("Dana Scully", identity.displayName());
    }

    @Test
    void entra_falls_back_to_the_upn_because_it_mints_no_verified_flag() {
        // A tenant-issued account IS the verification; `email` is an optional claim an admin may
        // not have turned on, and preferred_username is the UPN.
        VerifiedClaims verified = verifier.verify(token(header(KEY_ID, "RS256"), entraClaims()), NONCE)
                .orElseThrow();
        assertEquals("", verified.email());
        assertFalse(verified.emailVerified(), "Entra does not mint the flag Google's rule reads");

        ExternalIdentity identity = MicrosoftOAuth.identityFrom(verified).orElseThrow();
        assertEquals(AuthProviderId.MICROSOFT, identity.provider());
        assertEquals("dana@contoso.com", identity.email());
    }

    @Test
    void a_upn_that_is_not_an_address_is_refused_rather_than_enrolled() {
        // On-premises-synced accounts can carry a UPN that is not an address at all. Keying an
        // account on one would put a non-address into the allowlist's domain comparison.
        assertTrue(MicrosoftOAuth.identityFrom(
                new VerifiedClaims("sub", "", false, "CONTOSO\\dana", "Dana")).isEmpty());
        assertTrue(MicrosoftOAuth.identityFrom(
                new VerifiedClaims("sub", "", false, "", "Dana")).isEmpty());
    }

    @Test
    void an_entra_email_claim_wins_over_the_upn_when_the_tenant_publishes_one() {
        ExternalIdentity identity = MicrosoftOAuth.identityFrom(
                new VerifiedClaims("sub", "dana@contoso.com", false, "dana@contoso.onmicrosoft.com", "Dana"))
                .orElseThrow();
        assertEquals("dana@contoso.com", identity.email());
    }

    private static JsonObject header(String keyId, String algorithm) {
        JsonObject header = new JsonObject();
        header.addProperty("alg", algorithm);
        header.addProperty("kid", keyId);
        header.addProperty("typ", "JWT");
        return header;
    }

    private static JsonObject googleClaims() {
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

    /** What Entra actually mints: a UPN, no email claim unless configured, and no verified flag. */
    private static JsonObject entraClaims() {
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", "https://accounts.google.com");   // this verifier's expectation
        claims.addProperty("aud", AUDIENCE);
        claims.addProperty("sub", "AAAAAAAAAAAAAAAAAAAAAA");
        claims.addProperty("exp", System.currentTimeMillis() / 1000 + 600);
        claims.addProperty("nonce", NONCE);
        claims.addProperty("preferred_username", "dana@contoso.com");
        claims.addProperty("name", "Dana Scully");
        return claims;
    }

    /** Signs whatever it is given — including headers no issuer would ever write. */
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
