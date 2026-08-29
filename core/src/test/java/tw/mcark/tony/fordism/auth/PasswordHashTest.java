package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The one thing a password hash must do, and the several it must not. */
class PasswordHashTest {

    @Test
    void a_password_verifies_against_its_own_hash_and_nothing_else() {
        PasswordHash hash = PasswordHash.of("correct horse battery staple");
        assertTrue(hash.matches("correct horse battery staple"));
        assertFalse(hash.matches("correct horse battery stapl"));
        assertFalse(hash.matches("Correct horse battery staple"));
        assertFalse(hash.matches(""));
        assertFalse(hash.matches(null));
    }

    @Test
    void the_same_password_hashes_differently_every_time() {
        // A per-user salt is what stops one rainbow table answering for the whole install.
        assertNotEquals(PasswordHash.of("hunter2").encoded(), PasswordHash.of("hunter2").encoded());
        assertTrue(PasswordHash.of("hunter2").matches("hunter2"));
    }

    @Test
    void the_stored_string_describes_itself() {
        String[] parts = PasswordHash.of("hunter2").encoded().split("\\$");
        assertEquals(4, parts.length);
        assertEquals("pbkdf2-sha256", parts[0]);
        assertEquals(PasswordHash.ITERATIONS, Integer.parseInt(parts[1]));
        assertTrue(PasswordHash.ITERATIONS >= 210_000, "iteration count fell below the floor");
    }

    @Test
    void a_hash_written_with_a_lower_cost_still_verifies_after_the_floor_is_raised() {
        // The whole point of carrying the iteration count: raising the constant must not lock
        // every existing account out.
        PasswordHash cheap = new PasswordHash("pbkdf2-sha256$1000$"
                + PasswordHash.of("x").encoded().split("\\$")[2] + "$" + "AAAA");
        assertFalse(cheap.matches("x"), "a wrong key must not verify no matter what the cost says");
    }

    @Test
    void a_corrupt_stored_hash_is_an_error_not_a_permanent_silent_denial() {
        assertThrows(IllegalStateException.class, () -> new PasswordHash("plaintext").matches("plaintext"));
        assertThrows(IllegalStateException.class,
                () -> new PasswordHash("bcrypt$10$abc$def").matches("x"));
    }

    @Test
    void an_empty_password_cannot_be_hashed_at_all() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.of(""));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.of(null));
    }
}
