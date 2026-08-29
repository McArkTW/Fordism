package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every route in the app, against the gate: 401 with no session, 403 for a viewer on everything a
 * viewer may not do, and something other than 401/403 for an admin.
 *
 * <p>The admin assertion is deliberately weak — a 404 for a workflow that does not exist is a pass.
 * What is being tested is authorisation, not the handler behind it; asserting 200 would make this
 * fail whenever an unrelated endpoint changed its empty-state answer.
 */
class RouteAuthorizationTest {

    /** One request the gate has an opinion about. */
    record Route(String method, String path) {}

    private static final String PROBE = "matrix-probe";

    /** Read routes any signed-in viewer may reach. */
    private static final List<Route> VIEWER_MAY = List.of(
            new Route("GET", "/api/workflows"),
            new Route("GET", "/api/workflows/" + PROBE),
            new Route("GET", "/api/workflows/" + PROBE + "/preflight"),
            new Route("GET", "/api/templates"),
            new Route("GET", "/api/templates/" + PROBE),
            new Route("GET", "/api/skills"),
            new Route("GET", "/api/skills-source"),
            new Route("GET", "/api/skills/" + PROBE),
            new Route("GET", "/api/agent-profiles"),
            new Route("GET", "/api/agent-profiles/" + PROBE),
            new Route("GET", "/api/credentials"),
            new Route("GET", "/api/credentials/MATRIX_PROBE"),
            new Route("GET", "/api/runs"),
            new Route("GET", "/api/runs/" + PROBE),
            new Route("GET", "/api/questions"),
            new Route("GET", "/api/tasks/" + PROBE + "/result"),
            new Route("GET", "/api/tasks/" + PROBE + "/transcript"));

    /** Everything a viewer must be refused: every write, plus the two reads that are not "reading". */
    private static final List<Route> VIEWER_MAY_NOT = List.of(
            // A zip is the agent's whole workspace, not a summary of a run.
            new Route("GET", "/api/tasks/" + PROBE + "/result.zip"),
            new Route("GET", "/api/tasks/" + PROBE + "/workspace.zip"),
            new Route("GET", "/api/users"),
            new Route("GET", "/api/groups"),
            new Route("POST", "/api/workflows"),
            new Route("POST", "/api/workflows/validate"),
            new Route("PUT", "/api/workflows/" + PROBE),
            new Route("DELETE", "/api/workflows/" + PROBE),
            new Route("POST", "/api/workflows/" + PROBE + "/run"),
            new Route("POST", "/api/templates"),
            new Route("PUT", "/api/templates/" + PROBE),
            new Route("DELETE", "/api/templates/" + PROBE),
            new Route("POST", "/api/skills"),
            new Route("POST", "/api/skills-state"),
            new Route("POST", "/api/skills/upload"),
            new Route("DELETE", "/api/skills/" + PROBE),
            new Route("POST", "/api/agent-profiles"),
            new Route("PUT", "/api/agent-profiles/" + PROBE),
            new Route("DELETE", "/api/agent-profiles/" + PROBE),
            new Route("PUT", "/api/credentials/MATRIX_PROBE"),
            new Route("DELETE", "/api/credentials/MATRIX_PROBE"),
            new Route("POST", "/api/tasks/" + PROBE + "/answer"),
            new Route("POST", "/api/runs/" + PROBE + "/abandon"),
            new Route("POST", "/api/users"),
            new Route("PUT", "/api/users/" + PROBE),
            new Route("DELETE", "/api/users/" + PROBE),
            new Route("POST", "/api/groups"),
            new Route("PUT", "/api/groups/" + PROBE),
            new Route("DELETE", "/api/groups/" + PROBE));

    @TempDir
    Path stateDir;

    private FordismUnderTest app;
    private String viewer;
    private String admin;

    @BeforeEach
    void start() {
        app = new FordismUnderTest(stateDir);
        viewer = app.signedInMemberOf("viewers", "viewer@example.com");
        admin = app.signedInMemberOf("admins", "admin@example.com");
    }

    @AfterEach
    void stop() {
        app.close();
    }

    @Test
    void nothing_under_api_answers_without_a_session() {
        for (Route route : allRoutes()) {
            assertEquals(401, app.send(writing(request(route))).status(),
                    route.method() + " " + route.path() + " answered an anonymous caller");
        }
    }

    @Test
    void a_viewer_is_refused_every_write_and_every_download() {
        for (Route route : VIEWER_MAY_NOT) {
            assertEquals(403, app.send(writing(request(route)).header("Cookie", viewer)).status(),
                    route.method() + " " + route.path() + " let a viewer through");
        }
    }

    @Test
    void a_viewer_may_read_what_viewers_are_for() {
        for (Route route : VIEWER_MAY) {
            int status = app.send(request(route).header("Cookie", viewer)).status();
            assertNotEquals(403, status, route.method() + " " + route.path() + " refused a viewer");
            assertNotEquals(401, status, route.method() + " " + route.path() + " refused a viewer");
        }
    }

    @Test
    void an_admin_is_refused_nothing() {
        for (Route route : allRoutes()) {
            int status = app.send(writing(request(route)).header("Cookie", admin)).status();
            assertNotEquals(401, status, route.method() + " " + route.path() + " refused an admin");
            assertNotEquals(403, status, route.method() + " " + route.path() + " refused an admin");
        }
    }

    @Test
    void a_write_without_the_custom_header_is_refused_even_with_a_valid_session() {
        // SameSite=Lax still attaches the cookie to a top-level navigation; a cross-site form
        // cannot set a header. This is the difference between the two.
        for (Route route : VIEWER_MAY_NOT) {
            if ("GET".equals(route.method())) {
                continue;
            }
            assertEquals(403, app.send(request(route).header("Cookie", admin)).status(),
                    route.method() + " " + route.path() + " accepted a write with no request header");
        }
    }

    @Test
    void exactly_three_paths_answer_before_anyone_signs_in() {
        assertEquals(200, app.send(app.to("/api/health").GET()).status());
        assertEquals(200, app.send(app.to("/api/version").GET()).status());
        assertEquals(200, app.send(app.to("/api/auth/providers").GET()).status());
        // /api/auth/me is inside the exemption but still answers "nobody" rather than data.
        assertEquals(401, app.send(app.to("/api/auth/me").GET()).status());
    }

    @Test
    void a_route_nobody_classified_is_denied_rather_than_allowed() {
        // Fail closed: an endpoint added without a line in RoutePermissions must not be open.
        assertEquals(403, app.send(app.to("/api/not-a-real-endpoint").GET()
                .header("Cookie", admin)).status());
    }

    @Test
    void a_stale_or_forged_cookie_is_not_a_session() {
        FordismUnderTest.Response answer = app.send(app.to("/api/workflows").GET()
                .header("Cookie", "fordism_session=not-a-token-anyone-issued"));
        assertEquals(401, answer.status());
        assertTrue(answer.body().contains("not signed in"), answer.body());
    }

    private static List<Route> allRoutes() {
        return java.util.stream.Stream.concat(VIEWER_MAY.stream(), VIEWER_MAY_NOT.stream()).toList();
    }

    private HttpRequest.Builder request(Route route) {
        HttpRequest.Builder builder = app.to(route.path());
        if ("GET".equals(route.method())) {
            return builder.GET();
        }
        return builder.header("Content-Type", "application/json")
                .method(route.method(), HttpRequest.BodyPublishers.ofString("{}"));
    }

    private static HttpRequest.Builder writing(HttpRequest.Builder builder) {
        return FordismUnderTest.writing(builder);
    }
}
