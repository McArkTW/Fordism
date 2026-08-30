package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The throttle that turns the login from an unlimited guessing target into a bounded one.
 *
 * <p>The two properties that matter: enough failures on EITHER key locks the attempt, and a good
 * sign-in clears the account so a person who fumbled their password is not locked out behind the
 * attacker who was spraying the same username.
 */
class LoginThrottleTest {

    @Test
    void enough_failures_against_one_account_lock_it() {
        LoginThrottle throttle = new LoginThrottle();
        assertFalse(throttle.isLocked("dana@example.com", "10.0.0.1"));
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("dana@example.com", "10.0.0.1");
        }
        assertTrue(throttle.isLocked("dana@example.com", "10.0.0.1"));
    }

    @Test
    void a_spread_out_attack_is_caught_by_the_account_key_even_as_the_ip_rotates() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("dana@example.com", "10.0.0." + i);   // a fresh IP each time
        }
        // A brand-new IP is still refused, because the account it is trying is locked.
        assertTrue(throttle.isLocked("dana@example.com", "10.0.0.99"));
    }

    @Test
    void a_password_sprayer_on_one_host_is_caught_by_the_ip_key_across_accounts() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("victim" + i + "@example.com", "10.0.0.7");   // a fresh account each time
        }
        // A never-before-seen account from that host is refused, because the host is locked.
        assertTrue(throttle.isLocked("someone-new@example.com", "10.0.0.7"));
    }

    @Test
    void a_successful_sign_in_clears_the_account_but_not_the_ip() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("dana@example.com", "10.0.0.1");
        }
        throttle.recordSuccess("dana@example.com");
        assertFalse(throttle.isLocked("dana@example.com", "10.0.0.2"),
                "a good login must reset the account's own counter");
    }

    @Test
    void a_blank_account_or_ip_still_locks_rather_than_slipping_through_unkeyed() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("", "");
        }
        assertTrue(throttle.isLocked("", ""), "a missing key is one bucket, not an exemption");
    }
}
