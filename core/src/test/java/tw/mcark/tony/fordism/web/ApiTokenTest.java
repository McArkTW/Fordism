package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * API tokens against the real app: what one can do, what it can never do, and what stops it.
 *
 * <p>The two rules worth failing a build over are that a token NARROWS and never widens — the
 * intersection of its own grants and its owner's — and that it cannot mint another. A token that
 * could exceed its owner would make the groups page a lie; a token that could mint another would
 * be a leak with no end, since revoking the one that got out would not revoke its children.
 */
class ApiTokenTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path stateDir;

    private FordismUnderTest app;
    private String maintainer;
    private String viewer;

    @BeforeEach
    void start() {
        app = new FordismUnderTest(stateDir);
        maintainer = app.signedInMemberOf("maintainers", "maintainer@example.com");
        viewer = app.signedInMemberOf("viewers", "viewer@example.com");
    }

    @AfterEach
    void stop() {
        app.close();
    }

    @Test
    void a_minted_token_authenticates_a_request_its_owner_could_make() {
        String token = mint(maintainer, "{\"name\":\"ci\"}");

        assertEquals(200, app.send(app.to("/api/workflows").GET()
                .header("Authorization", "Bearer " + token)).status());
    }

    @Test
    void the_value_is_shown_once_and_never_appears_in_the_list() {
        String token = mint(maintainer, "{\"name\":\"ci\"}");

        String listed = app.send(app.to("/api/api-tokens").GET().header("Cookie", maintainer)).body();

        assertFalse(listed.contains(token), "the list must not carry the value: " + listed);
        assertFalse(listed.contains("value"), "the row shape has no field for it: " + listed);
        JsonArray rows = GSON.fromJson(listed, JsonArray.class);
        assertEquals(1, rows.size());
        assertEquals("ci", rows.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void a_token_narrower_than_its_owner_is_refused_what_it_did_not_ask_for() {
        String token = mint(maintainer, "{\"name\":\"read-only\",\"grants\":[\"workflow.read\"]}");

        assertEquals(200, app.send(app.to("/api/workflows").GET()
                .header("Authorization", "Bearer " + token)).status());
        FordismUnderTest.Response refused = app.send(app.to("/api/templates").GET()
                .header("Authorization", "Bearer " + token));
        assertEquals(403, refused.status());
        assertTrue(refused.body().contains("API token"),
                "the refusal must say which half said no: " + refused.body());
    }

    @Test
    void a_token_can_never_exceed_the_account_that_minted_it() {
        // A viewer asking for everything gets a token that can still only read.
        String token = mint(viewer, "{\"name\":\"greedy\",\"grants\":[\"*\"]}");

        assertEquals(200, app.send(app.to("/api/workflows").GET()
                .header("Authorization", "Bearer " + token)).status());
        assertEquals(403, app.send(app.to("/api/users").GET()
                .header("Authorization", "Bearer " + token)).status());
    }

    @Test
    void a_token_cannot_mint_or_revoke_a_token() {
        String token = mint(maintainer, "{\"name\":\"ci\",\"grants\":[\"*\"]}");

        FordismUnderTest.Response minting = app.send(app.to("/api/api-tokens")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"child\"}")));
        assertEquals(403, minting.status());
        assertTrue(minting.body().contains("sign in"), minting.body());

        assertEquals(403, app.send(app.to("/api/api-tokens").GET()
                .header("Authorization", "Bearer " + token)).status());
    }

    @Test
    void a_token_needs_no_csrf_header_because_nothing_attaches_one_for_a_victim() {
        String token = mint(maintainer, "{\"name\":\"ci\",\"grants\":[\"*\"]}");

        // No X-Fordism-Request. A cookie would be refused here; a bearer token is not a browser.
        int status = app.send(app.to("/api/workflows/does-not-exist/run")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))).status();
        assertNotEquals(403, status, "a token write must not be asked for the CSRF header");
        assertNotEquals(401, status);
    }

    @Test
    void a_revoked_token_stops_working_immediately() {
        String token = mint(maintainer, "{\"name\":\"ci\"}");
        String id = onlyTokenId(maintainer);

        assertEquals(204, app.send(FordismUnderTest.writing(
                app.to("/api/api-tokens/" + id).DELETE()).header("Cookie", maintainer)).status());

        assertEquals(401, app.send(app.to("/api/workflows").GET()
                .header("Authorization", "Bearer " + token)).status());
    }

    @Test
    void deleting_the_account_revokes_its_tokens_as_well_as_its_sessions() {
        String token = mint(viewer, "{\"name\":\"ci\"}");
        String admin = app.signedInMemberOf("admins", "admin@example.com");
        String viewerId = app.accounts().users().findByEmail("viewer@example.com").orElseThrow().id();

        assertEquals(204, app.send(FordismUnderTest.writing(
                app.to("/api/users/" + viewerId).DELETE()).header("Cookie", admin)).status());

        assertEquals(401, app.send(app.to("/api/workflows").GET()
                .header("Authorization", "Bearer " + token)).status(),
                "a token outlives a browser by design, so deleting the account must revoke it");
    }

    @Test
    void a_grant_that_matches_no_permission_is_refused_at_creation() {
        // A token whose grants are typos does nothing, and every call it makes is refused for a
        // reason that reads like a permissions problem. Say so here instead.
        FordismUnderTest.Response refused = app.send(FordismUnderTest.writing(app.to("/api/api-tokens")
                .header("Cookie", maintainer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"typo\",\"grants\":[\"workflow.reed\"]}"))));
        assertEquals(400, refused.status());
        assertTrue(refused.body().contains("workflow.read"), refused.body());
    }

    @Test
    void a_bearer_token_nobody_issued_is_not_a_session() {
        FordismUnderTest.Response answer = app.send(app.to("/api/workflows").GET()
                .header("Authorization", "Bearer fordism_pat_not-a-token-anyone-issued"));
        assertEquals(401, answer.status());
        assertTrue(answer.body().contains("not signed in"), answer.body());
    }

    /** Mint a token through the API the way the account page does, and return its value. */
    private String mint(String cookie, String body) {
        FordismUnderTest.Response created = app.send(FordismUnderTest.writing(app.to("/api/api-tokens")
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))));
        assertEquals(201, created.status(), created.body());
        JsonObject minted = GSON.fromJson(created.body(), JsonObject.class);
        String value = minted.get("value").getAsString();
        assertTrue(value.startsWith("fordism_pat_"), "a token should be recognisable as one: " + value);
        return value;
    }

    private String onlyTokenId(String cookie) {
        JsonArray rows = GSON.fromJson(
                app.send(app.to("/api/api-tokens").GET().header("Cookie", cookie)).body(), JsonArray.class);
        assertEquals(1, rows.size());
        return rows.get(0).getAsJsonObject().get("id").getAsString();
    }
}
