package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Who an OAuth sign-in is allowed to be. No network: the identity is the thing a verified callback
 * would have produced, and everything after that is this instance's own policy.
 */
class EnrollmentTest {

    @TempDir
    Path stateDir;

    private UserStore users;
    private GroupStore groups;

    @BeforeEach
    void setUp() {
        users = new UserStore(stateDir);
        groups = new GroupStore(stateDir);
        SeededGroups.into(groups);
    }

    private static AuthConfiguration allowing(String emails, String domains) {
        return AuthConfiguration.from(Map.of(
                "FORDISM_AUTH_LOCAL", "true",
                "FORDISM_ADMIN_SECRET", "test-secret",
                "FORDISM_AUTH_ALLOWED_EMAILS", emails,
                "FORDISM_AUTH_ALLOWED_DOMAINS", domains));
    }

    private static ExternalIdentity google(String subject, String email) {
        return new ExternalIdentity(AuthProviderId.GOOGLE, subject, email, "Someone");
    }

    @Test
    void a_repeat_sign_in_is_found_by_the_linked_subject_not_the_address() {
        User existing = users.create(new User(null, "dana@example.com", "Dana", "",
                java.util.List.of(new LinkedIdentity(AuthProviderId.GOOGLE, "sub-1")), null));
        Enrollment enrollment = new Enrollment(allowing("", ""), users);

        // The address on the provider side changed; the subject did not, so it is the same person.
        User signedIn = enrollment.resolve(google("sub-1", "dana.scully@example.com")).orElseThrow();
        assertEquals(existing.id(), signedIn.id());
        assertEquals("dana@example.com", signedIn.email());
        assertEquals(1, users.all().size());
    }

    @Test
    void a_first_oauth_sign_in_for_an_existing_account_links_the_identity_once() {
        User existing = users.create(User.withPassword("dana@example.com", "Dana", PasswordHash.of("hunter2xx")));
        Enrollment enrollment = new Enrollment(allowing("", ""), users);

        User signedIn = enrollment.resolve(google("sub-1", "DANA@example.com")).orElseThrow();
        assertEquals(existing.id(), signedIn.id());
        assertTrue(signedIn.isLinkedTo(new LinkedIdentity(AuthProviderId.GOOGLE, "sub-1")));
        // Linked, not duplicated — and the password they already had still works.
        assertEquals(1, users.all().size());
        assertTrue(users.find(existing.id()).orElseThrow().password().orElseThrow().matches("hunter2xx"));

        enrollment.resolve(google("sub-1", "dana@example.com"));
        assertEquals(1, users.find(existing.id()).orElseThrow().identities().size());
    }

    @Test
    void an_unknown_address_on_the_allowlist_enrols_with_no_groups_at_all() {
        Enrollment enrollment = new Enrollment(allowing("Dana@Example.com", ""), users);
        User enrolled = enrollment.resolve(google("sub-9", "dana@example.com")).orElseThrow();

        assertEquals("dana@example.com", enrolled.email());
        assertFalse(enrolled.hasPassword());
        // No groups means no permissions: they can see that they are signed in, and nothing else.
        assertTrue(groups.grantsFor(enrolled.id()).isEmpty());
        for (Permission permission : Permission.values()) {
            assertFalse(groups.allows(enrolled.id(), permission), permission.id());
        }
    }

    @Test
    void an_unknown_address_in_an_allowed_domain_enrols() {
        Enrollment enrollment = new Enrollment(allowing("", "Example.COM, other.test"), users);
        assertTrue(enrollment.resolve(google("sub-9", "newcomer@EXAMPLE.com")).isPresent());
        assertTrue(enrollment.admits("someone@other.test"));
        assertFalse(enrollment.admits("someone@notexample.com"));
    }

    @Test
    void an_unknown_address_on_neither_list_is_refused_and_creates_nothing() {
        Enrollment enrollment = new Enrollment(allowing("dana@example.com", "example.org"), users);
        assertTrue(enrollment.resolve(google("sub-9", "stranger@elsewhere.test")).isEmpty());
        assertTrue(users.all().isEmpty());
    }

    @Test
    void with_both_lists_empty_nothing_auto_enrols() {
        // The safe default: an install that has not said who may join has not said "anybody".
        Enrollment enrollment = new Enrollment(allowing("", ""), users);
        assertTrue(enrollment.resolve(google("sub-9", "anyone@anywhere.test")).isEmpty());
        assertFalse(enrollment.admits("anyone@anywhere.test"));
        assertTrue(users.all().isEmpty());
    }

    @Test
    void a_subdomain_is_not_the_allowed_domain() {
        Enrollment enrollment = new Enrollment(allowing("", "example.com"), users);
        assertFalse(enrollment.admits("someone@evil.example.com.attacker.test"));
        assertFalse(enrollment.admits("someone@notexample.com"));
        assertFalse(enrollment.admits("no-at-sign"));
        assertFalse(enrollment.admits(""));
    }
}
