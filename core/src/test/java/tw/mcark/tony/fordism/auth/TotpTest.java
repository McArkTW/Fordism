package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TOTP, checked against RFC 6238's own test vector and against the properties a second factor
 * rests on: a stale code is refused, one step of clock skew is tolerated, and nonsense does not
 * throw.
 */
class TotpTest {

    /**
     * The RFC 6238 appendix-B vector for SHA-1: the ASCII secret "12345678901234567890" is Base32
     * "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", and at Unix time 59 the code is 94287082.
     */
    // The published RFC 6238 test vector, not a credential — high entropy, so it is allowlisted.
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"; // gitleaks:allow

    @Test
    void it_matches_the_rfc_6238_reference_vector() {
        assertEquals(true, Totp.verifyAt(RFC_SECRET, "287082", 59),
                "the low 6 digits of the RFC's 8-digit 94287082");
    }

    @Test
    void a_freshly_generated_secret_verifies_its_own_current_code() {
        String secret = Totp.newSecret();
        long now = System.currentTimeMillis() / 1000L;
        assertTrue(Totp.verifyAt(secret, Totp.codeForEpoch(secret, now), now));
    }

    @Test
    void one_step_of_clock_skew_is_tolerated_but_two_is_not() {
        String secret = Totp.newSecret();
        long now = 10_000L * 30;   // a clean step boundary
        String code = Totp.codeForEpoch(secret, now);   // the EXACT code for this step, no fuzz
        assertTrue(Totp.verifyAt(secret, code, now + 30), "a phone one step ahead still works");
        assertTrue(Totp.verifyAt(secret, code, now - 30), "one step behind too");
        assertFalse(Totp.verifyAt(secret, code, now + 90), "two steps is stale");
        assertFalse(Totp.verifyAt(secret, code, now - 90), "two steps behind is stale");
    }

    @Test
    void the_wrong_code_and_malformed_input_are_refused_not_thrown() {
        String secret = Totp.newSecret();
        assertFalse(Totp.verify(secret, "000000"));
        assertFalse(Totp.verify(secret, ""));
        assertFalse(Totp.verify(secret, null));
        assertFalse(Totp.verify(secret, "12345"));   // wrong length
        assertFalse(Totp.verify(secret, "abcdef"));
    }

    @Test
    void the_provisioning_uri_carries_the_secret_issuer_and_account() {
        String uri = Totp.provisioningUri("ABC234", "dana@example.com", "Fordism");
        assertTrue(uri.startsWith("otpauth://totp/"), uri);
        assertTrue(uri.contains("secret=ABC234"), uri);
        assertTrue(uri.contains("issuer=Fordism"), uri);
        assertTrue(uri.contains("dana%40example.com"), uri);
    }
}
