package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import tw.mcark.tony.fordism.skill.SkillState;
import tw.mcark.tony.fordism.skill.SkillStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every {@link SkillController} response is JSON a client can parse, and a name the store refuses
 * is the caller's fault.
 *
 * <p>Three of these bodies used to be built by string concatenation
 * ({@code "{\"name\":\"" + name + "\"}"}) beside a file that encodes everything else through
 * {@link Api}/gson, so a name carrying a quote or a backslash produced a body no client could
 * read — and {@code delete} answered 500 for a name it had itself rejected as invalid.
 *
 * <p>Routes are mounted here rather than through {@link FordismUnderTest} on purpose: that harness
 * builds its {@code SkillStore} from the environment, and these tests write skills.
 */
class SkillControllerJsonTest {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Javalin app;
    private int port;

    @BeforeEach
    void start(@TempDir Path directory) {
        SkillStore skills = new SkillStore(directory.resolve("skills"),
                new SkillState(directory.resolve("state")));
        SkillController api = new SkillController(skills);
        app = Javalin.create().start(0);
        app.get("/api/skills/<name>", api::get);
        app.post("/api/skills", api::save);
        app.delete("/api/skills/<name>", api::delete);
        port = app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    @Test
    void a_saved_skill_is_acknowledged_as_json() throws Exception {
        Response saved = post("/api/skills",
                "{\"name\":\"mine/notes\",\"content\":\"---\\ndescription: Notes\\n---\\n\"}");

        assertEquals(200, saved.status());
        assertEquals("mine/notes", saved.json().get("name").getAsString());
    }

    /**
     * A name that needs JSON escaping. Whether the store accepts it depends on the filesystem —
     * Windows refuses a quote in a path — so what is asserted is the invariant that holds either
     * way: the body parses. Under the concatenation it did not, on any platform that accepted
     * the name.
     */
    @Test
    void a_name_that_needs_escaping_still_comes_back_as_readable_json() throws Exception {
        Response saved = post("/api/skills", GSON.toJson(new Save("mine/a\"b\\c", "---\nx\n---\n")));

        assertNotNull(saved.json(), "the response body must be a JSON object");
        assertTrue(saved.status() == 200 || saved.status() == 400, "unexpected " + saved.status());
    }

    @Test
    void a_name_the_store_refuses_is_a_400_on_every_route() throws Exception {
        Response read = send("GET", "/api/skills/..%2Fescape", null);
        Response deleted = send("DELETE", "/api/skills/..%2Fescape", null);
        Response saved = post("/api/skills", GSON.toJson(new Save("../escape", "x")));

        assertEquals(400, read.status());
        assertEquals(400, deleted.status(), "an invalid name is the caller's mistake, not a 500");
        assertEquals(400, saved.status());
        assertEquals("invalid skill name", deleted.json().get("error").getAsString());
    }

    private record Save(String name, String content) {}

    private record Response(int status, String body) {
        JsonObject json() {
            try {
                return GSON.fromJson(body, JsonObject.class);
            } catch (JsonSyntaxException e) {
                return null;
            }
        }
    }

    private Response post(String path, String body) throws IOException, InterruptedException {
        return send("POST", path, body);
    }

    private Response send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(method, payload).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
