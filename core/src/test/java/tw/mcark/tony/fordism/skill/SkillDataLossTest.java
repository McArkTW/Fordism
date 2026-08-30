package tw.mcark.tony.fordism.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A user's own skill must survive anything that goes wrong around it. Both cases here are ones the
 * existing suites miss by never letting the two names collide: {@code SkillStoreTest} uploads a bad
 * folder only to a name that did not exist, and {@code SkillPluginStoreTest} names its plugin
 * {@code toolkit} and its hand-written skill {@code mine}.
 */
class SkillDataLossTest {

    @TempDir
    Path directory;

    private Path library;
    private Path state;
    private SkillStore skills;

    @BeforeEach
    void setUp() {
        library = directory.resolve("skills");
        state = directory.resolve("state");
        skills = new SkillStore(library, new SkillState(state));
    }

    /** A refused upload must leave the skill it was replacing exactly as it was. */
    @Test
    void an_upload_without_a_skill_md_does_not_destroy_the_skill_it_replaces() throws IOException {
        skills.write("mine/notes", "---\ndescription: Precious\n---\nbody\n");
        System.out.println("A | before        : " + names() + "  SKILL.md="
                + Files.exists(library.resolve("mine/notes/SKILL.md")));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
                skills.writeFolder("mine/notes", List.of("readme.txt"), streams("oops")));

        System.out.println("A | upload refused: " + refused.getMessage());
        System.out.println("A | after         : " + names() + "  SKILL.md="
                + Files.exists(library.resolve("mine/notes/SKILL.md")));
        assertTrue(Files.exists(library.resolve("mine/notes/SKILL.md")),
                "a refused upload wiped the skill that was already there");
    }

    /** A plugin whose repo name matches a hand-written folder must be refused, not silently take it. */
    @Test
    void a_plugin_does_not_take_over_a_hand_written_skill_folder() throws IOException {
        skills.write("mine/notes", "---\ndescription: Precious\n---\nbody\n");
        SkillPluginStore plugins = new SkillPluginStore(state, library,
                new StubHttp(repoZip("mine-abc123",
                        Map.of("skills/commit/SKILL.md", "---\ndescription: Commit\n---\n"))));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> plugins.add("https://github.com/acme/mine", "HEAD"));

        assertTrue(refused.getMessage().contains("already has"), refused.getMessage());
        assertTrue(names().contains("mine/notes"),
                "the plugin swallowed a hand-written skill that shared its folder name");
        assertTrue(plugins.list().isEmpty(), "a refused plugin must not be left in the registry");
    }

    /**
     * Deleting the last skill under a namespace leaves the empty directory behind. That leftover
     * must not lock the plugin name out for good — the first cut of the collision guard tested
     * existence rather than content, and an empty folder the UI does not show blocked the add.
     */
    @Test
    void an_empty_leftover_folder_does_not_block_a_plugin_name() throws IOException {
        skills.write("skills/my-runbook", "---\ndescription: Mine\n---\n");
        skills.delete("skills/my-runbook");
        assertTrue(Files.isDirectory(library.resolve("skills")), "precondition: the empty dir is left behind");

        SkillPluginStore plugins = new SkillPluginStore(state, library,
                new StubHttp(repoZip("skills-abc123",
                        Map.of("skills/commit/SKILL.md", "---\ndescription: Commit\n---\n"))));
        SkillPlugin added = plugins.add("https://github.com/anthropics/skills", "HEAD");

        assertEquals("", added.lastError());
        assertTrue(names().contains("skills/commit"), names().toString());
    }

    /**
     * The invariant behind both bugs above, stated once so a case nobody thought of still fails:
     * a hand-written skill is only ever touched by an operation that names it. Every mutating entry
     * point is driven here — including the ones that are supposed to fail, because that is where
     * both bugs lived.
     */
    @Test
    void nothing_but_an_operation_naming_a_skill_may_touch_it() throws IOException {
        skills.write("keep/one", "---\ndescription: One\n---\nbody one\n");
        skills.write("keep/two", "---\ndescription: Two\n---\nbody two\n");
        String before = fingerprint();

        // Each of these must leave keep/* exactly as it was, whether it succeeds or fails.
        List<Runnable> operations = List.of(
                () -> quietly(() -> skills.write("other/new", "---\ndescription: New\n---\n")),
                () -> quietly(() -> skills.delete("other/new")),
                () -> quietly(() -> skills.writeFolder("other/up", List.of("SKILL.md"),
                        streams("---\ndescription: Up\n---\n"))),
                () -> quietly(() -> skills.writeFolder("other/up", List.of("readme.txt"), streams("no contract"))),
                () -> quietly(() -> skills.writeFolder("other/up", List.of("../escape.txt"), streams("out"))),
                () -> quietly(() -> skills.writeFolder("other/up", List.of(), List.of())),
                () -> quietly(() -> newPlugins(repoZip("pack-1",
                        Map.of("skills/x/SKILL.md", "---\ndescription: X\n---\n")))
                        .add("https://github.com/acme/pack", "HEAD")),
                () -> quietly(() -> newPlugins(repoZip("keep-1",
                        Map.of("skills/x/SKILL.md", "---\ndescription: X\n---\n")))
                        .add("https://github.com/acme/keep", "HEAD")),
                () -> quietly(() -> newPlugins(new byte[] {1, 2, 3})
                        .add("https://github.com/acme/broken", "HEAD")));

        for (Runnable operation : operations) {
            operation.run();
            assertEquals(before, fingerprint(),
                    "an operation that never named keep/* changed it");
        }
    }

    /** Every kept skill's path and bytes — the thing that must not move. */
    private String fingerprint() throws IOException {
        StringBuilder out = new StringBuilder();
        for (String name : List.of("keep/one", "keep/two")) {
            Path file = library.resolve(name).resolve("SKILL.md");
            out.append(name).append('=')
               .append(Files.exists(file) ? Files.readString(file) : "<GONE>")
               .append('\n');
        }
        return out.toString();
    }

    private SkillPluginStore newPlugins(byte[] zip) {
        return new SkillPluginStore(state, library, new StubHttp(zip));
    }

    /** The operation's own outcome is not what this test is about — only its blast radius is. */
    private static void quietly(ThrowingOperation operation) {
        try {
            operation.run();
        } catch (Exception expected) {
            // Some of these are meant to fail; the assertion is on what they left behind.
        }
    }

    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private List<String> names() {
        return skills.list().stream().map(SkillView::name).toList();
    }

    private static List<InputStream> streams(String... bodies) {
        List<InputStream> out = new ArrayList<>();
        for (String body : bodies) {
            out.add(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static byte[] repoZip(String wrapper, Map<String, String> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(wrapper + "/" + file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** One canned zip, so the suite never reaches GitHub. */
    private static final class StubHttp extends HttpClient {
        private final byte[] body;

        StubHttp(byte[] body) {
            this.body = body;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (HttpResponse<T>) new StubResponse(request, 200, new ByteArrayInputStream(body));
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return java.util.concurrent.CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }

        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public javax.net.ssl.SSLParameters sslParameters() { throw new UnsupportedOperationException(); }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
    }

    private record StubResponse(HttpRequest request, int statusCode, InputStream body)
            implements HttpResponse<InputStream> {
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
        }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
