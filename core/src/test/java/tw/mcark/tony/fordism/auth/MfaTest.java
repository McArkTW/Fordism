package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The second-factor material on an account: that enrolment produces one-time recovery codes, that
 * redeeming one spends it, and that a spent code cannot be replayed.
 */
class MfaTest {

    @Test
    void enrolment_produces_ten_one_time_recovery_codes_and_stores_only_their_hashes() {
        Mfa.Enrolled enrolled = Mfa.enrol(Totp.newSecret());
        assertEquals(10, enrolled.recoveryCodes().size());
        assertEquals(10, enrolled.mfa().remainingRecoveryCodes());
        for (String code : enrolled.recoveryCodes()) {
            assertFalse(enrolled.mfa().recoveryCodeHashes().contains(code),
                    "the plaintext code must never be what is stored");
        }
    }

    @Test
    void a_recovery_code_works_once_and_the_returned_factor_has_it_spent() {
        Mfa.Enrolled enrolled = Mfa.enrol(Totp.newSecret());
        String code = enrolled.recoveryCodes().get(0);

        Optional<Mfa> after = enrolled.mfa().redeemRecoveryCode(code);
        assertTrue(after.isPresent(), "a valid code redeems");
        assertEquals(9, after.orElseThrow().remainingRecoveryCodes());

        // Replaying the same code against the RETURNED factor (the one that would be persisted) fails.
        assertTrue(after.orElseThrow().redeemRecoveryCode(code).isEmpty(), "single use");
    }

    @Test
    void a_code_is_matched_however_it_is_typed_back() {
        Mfa.Enrolled enrolled = Mfa.enrol(Totp.newSecret());
        String code = enrolled.recoveryCodes().get(0);
        // Dashes and case are cosmetic; the stored hash is of the normalized form.
        assertTrue(enrolled.mfa().redeemRecoveryCode(code.toUpperCase().replace("-", " ")).isPresent());
    }

    @Test
    void garbage_and_blanks_redeem_nothing() {
        Mfa.Enrolled enrolled = Mfa.enrol(Totp.newSecret());
        assertTrue(enrolled.mfa().redeemRecoveryCode("not-a-code").isEmpty());
        assertTrue(enrolled.mfa().redeemRecoveryCode("").isEmpty());
        assertTrue(enrolled.mfa().redeemRecoveryCode(null).isEmpty());
        assertEquals(10, enrolled.mfa().remainingRecoveryCodes());
    }

    @Test
    void a_factor_with_a_blank_secret_is_not_enabled() {
        assertFalse(new Mfa("", List.of()).enabled());
        assertTrue(new Mfa(Totp.newSecret(), List.of()).enabled());
    }
}
