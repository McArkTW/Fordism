package tw.mcark.tony.fordism.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin owns one folder in the library. Adding it unpacks the repo's skills there, syncing
 * replaces them, removing deletes them — and none of the three ever reaches a skill the user wrote.
 */
class SkillPluginStoreTest {

    @TempDir
    Path directory;

    private Path library;
    private Path state;
    private StubHttp http;
    private SkillStore skills;

    @BeforeEach
    void setUp() {
        library = directory.resolve("skills");
        state = directory.resolve("state");
        http = new StubHttp();
        skills = new SkillStore(library, new SkillState(state));
    }

    @Test
    void adding_a_plugin_unpacks_its_skills_under_its_own_name() throws IOException {
        http.answerWith(repoZip("toolkit-9f2a1c", Map.of(
                "skills/commit/SKILL.md", "---\ndescription: Commit\n---\n",
                "skills/review/SKILL.md", "---\ndescription: Review\n---\n")));

        SkillPlugin added = store().add("https://github.com/acme/toolkit", "v1.0.0");

        assertEquals("toolkit", added.name());
        assertEquals("", added.lastError());
        assertFalse(added.lastSyncedAt().isEmpty());
        assertEquals(List.of("toolkit/commit", "toolkit/review"),
                skills.list().stream().map(SkillView::name).toList());
    }

    /** The repo's own scaffolding is not a skill: only the folders holding a SKILL.md come across. */
    @Test
    void the_archive_wrapper_and_the_plugin_manifest_are_not_installed_as_skills() throws IOException {
        http.answerWith(repoZip("toolkit-9f2a1c", Map.of(
                ".claude-plugin/plugin.json", "{\"name\":\"toolkit\"}",
                "README.md", "# Toolkit\n",
                "skills/commit/SKILL.md", "---\ndescription: Commit\n---\n")));

        store().add("acme/toolkit", "HEAD");

        assertEquals(List.of("toolkit/commit"), skills.list().stream().map(SkillView::name).toList());
        assertFalse(Files.exists(library.resolve("toolkit/README.md")));
    }

    @Test
    void a_repo_without_a_skills_directory_still_installs_its_skill_folders() throws IOException {
        http.answerWith(repoZip("plain-1a2b3c", Map.of(
                "deploy/SKILL.md", "---\ndescription: Deploy\n---\n")));

        store().add("acme/plain", "HEAD");

        assertEquals(List.of("plain/deploy"), skills.list().stream().map(SkillView::name).toList());
    }

    @Test
    void syncing_replaces_the_plugin_folder_so_an_upstream_deletion_propagates() throws IOException {
        http.answerWith(repoZip("toolkit-1", Map.of(
                "skills/commit/SKILL.md", "---\ndescription: Commit\n---\n",
                "skills/dropped/SKILL.md", "---\ndescription: Dropped\n---\n")));
        SkillPluginStore plugins = store();
        SkillPlugin added = plugins.add("acme/toolkit", "HEAD");

        http.answerWith(repoZip("toolkit-2", Map.of(
                "skills/commit/SKILL.md", "---\ndescription: Commit\n---\n")));
        plugins.sync(added.id());

        assertEquals(List.of("toolkit/commit"), skills.list().stream().map(SkillView::name).toList());
        assertFalse(Files.exists(library.resolve("toolkit/dropped")));
    }

    @Test
    void removing_a_plugin_deletes_its_skills_and_leaves_a_hand_written_one_alone() throws IOException {
        skills.write("mine/notes", "---\ndescription: Mine\n---\n");
        http.answerWith(repoZip("toolkit-1", Map.of(
                "skills/commit/SKILL.md", "---\ndescription: Commit\n---\n")));
        SkillPluginStore plugins = store();
        SkillPlugin added = plugins.add("acme/toolkit", "HEAD");

        plugins.remove(added.id());

        assertTrue(plugins.list().isEmpty());
        assertEquals(List.of("mine/notes"), skills.list().stream().map(SkillView::name).toList());
    }

    /** A plugin that stopped updating must not look like one that is up to date. */
    @Test
    void a_failed_sync_is_recorded_on_the_plugin_rather_than_thrown_away() throws IOException {
        http.answerWithStatus(404);

        SkillPlugin added = store().add("acme/missing", "HEAD");

        assertTrue(added.lastError().contains("404"), added.lastError());
        assertEquals("", added.lastSyncedAt());
    }

    @Test
    void the_registry_survives_a_restart() throws IOException {
        http.answerWith(repoZip("toolkit-1", Map.of(
                "skills/commit/SKILL.md", "---\ndescription: Commit\n---\n")));
        store().add("acme/toolkit", "HEAD");

        assertEquals(List.of("toolkit"), store().list().stream().map(SkillPlugin::name).toList());
    }

    @Test
    void the_same_repo_cannot_be_added_twice() throws IOException {
        http.answerWith(repoZip("toolkit-1", Map.of(
                "skills/commit/SKILL.md", "---\ndescription: Commit\n---\n")));
        SkillPluginStore plugins = store();
        plugins.add("acme/toolkit", "HEAD");

        assertThrows(IllegalArgumentException.class, () -> plugins.add("acme/toolkit", "HEAD"));
    }

    @Test
    void a_github_repo_resolves_to_its_codeload_archive() {
        assertEquals("https://codeload.github.com/acme/toolkit/zip/v1.0.0",
                SkillPluginStore.archiveUrl("https://github.com/acme/toolkit.git", "v1.0.0"));
        assertEquals("https://codeload.github.com/acme/toolkit/zip/HEAD",
                SkillPluginStore.archiveUrl("acme/toolkit", "HEAD"));
        assertEquals("https://example.com/build/skills.zip",
                SkillPluginStore.archiveUrl("https://example.com/build/skills.zip", "HEAD"));
    }

    /**
     * A GitHub archive URL ends in the ref, not the repo, so the last segment would name the
     * plugin {@code main} — and that URL is what GitHub's "Download ZIP" button hands out.
     */
    @Test
    void a_github_archive_zip_is_named_after_the_repo_not_the_ref() {
        assertEquals("skills", SkillPluginStore.folderName(
                "https://github.com/anthropics/skills/archive/refs/heads/main.zip"));
        assertEquals("toolkit", SkillPluginStore.folderName(
                "https://github.com/acme/toolkit/archive/refs/tags/v1.0.0.zip"));
        assertEquals("toolkit", SkillPluginStore.folderName(
                "https://github.com/acme/toolkit/archive/9f2a1c.zip"));
        // Anything that is not a GitHub archive still reads its name off the last segment.
        assertEquals("skills", SkillPluginStore.folderName("https://example.com/build/skills.zip"));
        assertEquals("toolkit", SkillPluginStore.folderName("https://github.com/acme/toolkit"));
    }

    /**
     * A URL that can never resolve leaves nothing behind. It used to be registered anyway — {@code
     * add} persisted the plugin and let the first {@code sync} record the failure as {@code
     * lastError}, which is right for a 404 that may pass on retry but wrong for a URL shape that
     * never will: the registry filled with unusable rows named after whatever the URL ended in
     * ({@code HEAD}, {@code zip}, {@code anything}), each needing a manual Remove.
     */
    @Test
    void a_url_that_can_never_resolve_is_refused_rather_than_registered() {
        SkillPluginStore plugins = store();

        assertThrows(IllegalArgumentException.class,
                () -> plugins.add("https://codeload.github.com/acme/toolkit/zip/HEAD", "HEAD"));
        assertThrows(IllegalArgumentException.class,
                () -> plugins.add("http://127.0.0.1:8080/anything.zip", "HEAD"));

        assertTrue(plugins.list().isEmpty(), "a refused URL must not reach the registry");
    }

    /** A fetch that could pass on a retry still registers, so Sync has something to retry. */
    @Test
    void a_resolvable_url_that_fails_to_fetch_is_registered_with_its_error() throws Exception {
        http.answerWithStatus(404);

        SkillPlugin added = store().add("acme/toolkit", "HEAD");

        assertEquals("", added.lastSyncedAt());
        assertTrue(added.lastError().contains("404"), added.lastError());
    }

    /** The class javadoc says the repo arrives over HTTPS; this is what makes that true. */
    @Test
    void a_plain_http_zip_url_is_refused() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillPluginStore.archiveUrl("http://169.254.169.254/latest.zip", "HEAD"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillPluginStore.archiveUrl("http://localhost:8090/api/tasks/1/workspace.zip", "HEAD"));
    }

    @Test
    void a_url_that_is_neither_a_github_repo_nor_a_zip_is_refused() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillPluginStore.archiveUrl("https://example.com/not-a-repo/deep/path", "HEAD"));
    }

    private SkillPluginStore store() {
        return new SkillPluginStore(state, library, http);
    }

    /** A GitHub zip wraps the whole repo in one {@code <repo>-<sha>/} directory. */
    private static byte[] repoZip(String wrapper, Map<String, String> files) {
        Map<String, String> wrapped = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : new java.util.TreeMap<>(files).entrySet()) {
            wrapped.put(wrapper + "/" + file.getKey(), file.getValue());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : wrapped.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /**
     * A stub over {@link HttpClient}: the store's only outside dependency is one GET, and a suite
     * that reaches GitHub is a suite that fails on a train.
     */
    private static final class StubHttp extends HttpClient {
        private byte[] body = new byte[0];
        private int status = 200;

        void answerWith(byte[] zip) {
            this.body = zip;
            this.status = 200;
        }

        void answerWithStatus(int status) {
            this.status = status;
            this.body = new byte[0];
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (HttpResponse<T>) new StubResponse(request, status,
                    new ByteArrayInputStream(body));
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

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<java.time.Duration> connectTimeout() {
            return java.util.Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() {
            return java.util.Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() {
            return java.util.Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() {
            return java.util.Optional.empty();
        }
    }

    private record StubResponse(HttpRequest request, int statusCode, InputStream body)
            implements HttpResponse<InputStream> {

        @Override
        public java.util.Optional<HttpResponse<InputStream>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
