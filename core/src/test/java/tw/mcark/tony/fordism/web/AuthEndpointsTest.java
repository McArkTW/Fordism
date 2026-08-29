package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.auth.Group;
import tw.mcark.tony.fordism.auth.SeededGroups;
import tw.mcark.tony.fordism.auth.User;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sign-in endpoints, over HTTP, on the real app.
 *
 * <p>The bootstrap is the one that matters most: it is the only way into an unclaimed instance and
 * therefore the only door that must shut permanently, on the first account rather than on the first
 * admin, and regardless of who still knows the secret.
 */
class AuthEndpointsTest {

    @TempDir
    Path stateDir;

    private FordismUnderTest app;

    @BeforeEach
    void start() {
        app = new FordismUnderTest(stateDir);
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private FordismUnderTest.Response post(String path, String body) {
        return app.send(app.to(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String bootstrapBody(String secret) {
        return "{\"secret\":\"" + secret + "\",\"email\":\"admin@example.com\","
                + "\"password\":\"a-good-enough-password\",\"displayName\":\"The Admin\"}";
    }

    @Test
    void a_fresh_install_says_it_needs_bootstrapping_and_then_stops_saying_so() {
        assertTrue(app.send(app.to("/api/auth/providers").GET()).body().contains("\"bootstrapRequired\":true"));
        assertEquals(200, post("/api/auth/bootstrap", bootstrapBody(FordismUnderTest.ADMIN_SECRET)).status());
        assertTrue(app.send(app.to("/api/auth/providers").GET()).body().contains("\"bootstrapRequired\":false"));
    }

    @Test
    void bootstrapping_creates_an_admin_in_the_seeded_group_and_signs_them_in() {
        FordismUnderTest.Response answer = post("/api/auth/bootstrap",
                bootstrapBody(FordismUnderTest.ADMIN_SECRET));
        assertEquals(200, answer.status());
        assertTrue(answer.body().contains("\"admins\""), answer.body());
        assertTrue(answer.body().contains("\"*\""), answer.body());

        // The response carried a session, and it works.
        String cookie = answer.sessionCookie();
        assertTrue(cookie.startsWith("fordism_session="));
        assertEquals(200, app.send(app.to("/api/users").GET().header("Cookie", cookie)).status());
    }

    @Test
    void the_bootstrap_shuts_on_the_first_account_and_the_right_secret_no_longer_opens_it() {
        assertEquals(200, post("/api/auth/bootstrap", bootstrapBody(FordismUnderTest.ADMIN_SECRET)).status());

        FordismUnderTest.Response again = post("/api/auth/bootstrap",
                bootstrapBody(FordismUnderTest.ADMIN_SECRET));
        assertEquals(403, again.status());
        assertTrue(again.body().contains("already has an account"), again.body());
        assertEquals(1, app.accounts().users().all().size());
    }

    @Test
    void an_account_created_any_other_way_also_closes_the_bootstrap() {
        // Not just "an admin exists" — any account at all. Otherwise an install whose only user was
        // auto-enrolled through OAuth would still be claimable by whoever guessed the secret.
        app.signedInMemberOf("viewers", "someone@example.com");
        assertEquals(403, post("/api/auth/bootstrap", bootstrapBody(FordismUnderTest.ADMIN_SECRET)).status());
    }

    @Test
    void a_wrong_secret_creates_nothing() {
        assertEquals(403, post("/api/auth/bootstrap", bootstrapBody("not-the-secret")).status());
        assertTrue(app.accounts().users().all().isEmpty());
        // And the door is still open for the person who does hold it.
        assertEquals(200, post("/api/auth/bootstrap", bootstrapBody(FordismUnderTest.ADMIN_SECRET)).status());
    }

    @Test
    void a_short_password_is_refused_before_an_account_exists_to_regret_it() {
        assertEquals(400, post("/api/auth/bootstrap", "{\"secret\":\"" + FordismUnderTest.ADMIN_SECRET
                + "\",\"email\":\"admin@example.com\",\"password\":\"short\"}").status());
        assertTrue(app.accounts().users().all().isEmpty());
    }

    @Test
    void logging_in_needs_the_right_password_and_says_no_more_than_that() {
        post("/api/auth/bootstrap", bootstrapBody(FordismUnderTest.ADMIN_SECRET));

        FordismUnderTest.Response wrong = post("/api/auth/login",
                "{\"email\":\"admin@example.com\",\"password\":\"guessing\"}");
        assertEquals(401, wrong.status());
        assertTrue(wrong.body().contains("wrong email or password"), wrong.body());

        // The same answer for an address that does not exist — otherwise this is a user enumerator.
        FordismUnderTest.Response unknown = post("/api/auth/login",
                "{\"email\":\"nobody@example.com\",\"password\":\"guessing\"}");
        assertEquals(401, unknown.status());
        assertEquals(wrong.body(), unknown.body());

        assertEquals(200, post("/api/auth/login",
                "{\"email\":\"ADMIN@example.com\",\"password\":\"a-good-enough-password\"}").status());
    }

    @Test
    void me_reports_the_groups_and_the_effective_grants_the_ui_draws_from() {
        String cookie = app.signedInMemberOf("operators", "op@example.com");
        String body = app.send(app.to("/api/auth/me").GET().header("Cookie", cookie)).body();
        assertTrue(body.contains("\"operators\""), body);
        assertTrue(body.contains("workflow.run"), body);
        assertTrue(body.contains("run.*"), body);
        assertFalse(body.contains("passwordHash"), body);
    }

    @Test
    void logging_out_ends_the_session_server_side_not_just_in_the_browser() {
        post("/api/auth/bootstrap", bootstrapBody(FordismUnderTest.ADMIN_SECRET));
        String cookie = post("/api/auth/login",
                "{\"email\":\"admin@example.com\",\"password\":\"a-good-enough-password\"}").sessionCookie();
        assertEquals(200, app.send(app.to("/api/users").GET().header("Cookie", cookie)).status());

        assertEquals(204, app.send(app.to("/api/auth/logout").header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody())).status());
        // The same cookie, replayed: the token is gone from the store, so it is nobody.
        assertEquals(401, app.send(app.to("/api/users").GET().header("Cookie", cookie)).status());
    }

    @Test
    void a_session_outlives_a_restart_because_it_is_on_disk() {
        String cookie = app.signedInMemberOf("admins", "admin@example.com");
        app.close();
        app = new FordismUnderTest(stateDir);
        assertEquals(200, app.send(app.to("/api/users").GET().header("Cookie", cookie)).status());
    }

    @Test
    void no_endpoint_ever_serialises_a_password_hash() {
        String cookie = app.signedInMemberOf("admins", "admin@example.com");
        String users = app.send(app.to("/api/users").GET().header("Cookie", cookie)).body();
        assertFalse(users.contains("pbkdf2"), users);
        assertFalse(users.contains("passwordHash"), users);
        // A stored password shows up as the "local" way in, and nothing more than that.
        assertTrue(users.contains("\"provider\":\"local\""), users);

        String created = app.send(FordismUnderTest.writing(app.to("/api/users")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"new@example.com\",\"password\":\"another-password\"}")))
                .header("Cookie", cookie)).body();
        assertFalse(created.contains("pbkdf2"), created);
        assertTrue(app.accounts().users().findByEmail("new@example.com").orElseThrow()
                .password().orElseThrow().matches("another-password"));
    }

    @Test
    void the_last_group_granting_everything_cannot_be_edited_or_deleted_away() {
        String cookie = app.signedInMemberOf("admins", "admin@example.com");
        Group admins = app.accounts().groups().findByName(SeededGroups.ADMINS).orElseThrow();

        assertEquals(409, groupEdit(cookie, admins.id(), "{\"grants\":[\"workflow.read\"]}").status());
        assertEquals(409, groupEdit(cookie, admins.id(), "{\"members\":[]}").status());
        assertEquals(409, app.send(FordismUnderTest.writing(app.to("/api/groups/" + admins.id())
                .method("DELETE", HttpRequest.BodyPublishers.noBody())).header("Cookie", cookie)).status());

        // The group survived all three, unedited.
        Group after = app.accounts().groups().findByName(SeededGroups.ADMINS).orElseThrow();
        assertTrue(after.grantsEverything());
        assertEquals(1, after.memberUserIds().size());

        // A group nobody depends on deletes fine — the guard is about lockout, not about groups.
        String viewers = app.accounts().groups().findByName("viewers").orElseThrow().id();
        assertEquals(204, app.send(FordismUnderTest.writing(app.to("/api/groups/" + viewers)
                .method("DELETE", HttpRequest.BodyPublishers.noBody())).header("Cookie", cookie)).status());
    }

    @Test
    void deleting_the_only_administrator_is_refused_from_the_users_page_too() {
        String cookie = app.signedInMemberOf("admins", "admin@example.com");
        User admin = app.accounts().users().findByEmail("admin@example.com").orElseThrow();

        assertEquals(409, app.send(FordismUnderTest.writing(app.to("/api/users/" + admin.id())
                .method("DELETE", HttpRequest.BodyPublishers.noBody())).header("Cookie", cookie)).status());
        assertTrue(app.accounts().users().find(admin.id()).isPresent());

        // With a second administrator in place the first can go.
        app.signedInMemberOf(SeededGroups.ADMINS, "second@example.com");
        assertEquals(204, app.send(FordismUnderTest.writing(app.to("/api/users/" + admin.id())
                .method("DELETE", HttpRequest.BodyPublishers.noBody())).header("Cookie", cookie)).status());
        assertTrue(app.accounts().users().find(admin.id()).isEmpty());
        // And they are out of the group they were in, not left behind as a dangling id.
        assertFalse(app.accounts().groups().findByName(SeededGroups.ADMINS).orElseThrow()
                .hasMember(admin.id()));
    }

    @Test
    void an_unknown_sign_in_provider_is_a_404_not_a_redirect_to_nowhere() {
        assertEquals(404, app.send(app.to("/api/auth/gitlab/login").GET()).status());
        // Google is not configured on this instance, so it is not offered and not startable.
        assertEquals(404, app.send(app.to("/api/auth/google/login").GET()).status());
        assertFalse(app.send(app.to("/api/auth/providers").GET()).body().contains("google"));
    }

    private FordismUnderTest.Response groupEdit(String cookie, String id, String body) {
        return app.send(FordismUnderTest.writing(app.to("/api/groups/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))).header("Cookie", cookie));
    }
}
