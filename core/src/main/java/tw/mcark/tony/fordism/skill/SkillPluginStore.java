package tw.mcark.tony.fordism.skill;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.tinylog.Logger;

/**
 * The plugins the skills library mirrors. Each plugin owns one folder under the library
 * ({@code <skillsDir>/<plugin>/}); adding, syncing and removing only ever touch that folder. The
 * folder name comes from the repo, so it can collide with a hand-written skill — {@link #add} refuses
 * that rather than taking the folder over, because a sync would then replace skills no plugin wrote.
 *
 * <p>The repo arrives as a zip over HTTPS rather than a clone: the core image carries curl and
 * ca-certificates but no git, and {@link SkillStore#extractZip} already unpacks an untrusted
 * archive safely.
 *
 * <p>The registry is persisted to {@code <stateDir>/skill-plugins.json} so it survives a redeploy;
 * the skills themselves live in the library beside every other skill.
 */
public final class SkillPluginStore {
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** {@code https://github.com/<owner>/<repo>/archive/<ref…>} — group 1 stops after the repo. */
    private static final Pattern GITHUB_ARCHIVE =
            Pattern.compile("(https?://(?:www[.])?github[.]com/[^/]+/[^/]+)/archive/.*");

    private final Path file;
    private final Path skillsRoot;
    private final HttpClient http;
    private final Map<String, SkillPlugin> plugins = new LinkedHashMap<>();

    public SkillPluginStore(FordismConfiguration configuration) {
        this(configuration, HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public SkillPluginStore(FordismConfiguration configuration, HttpClient http) {
        this(Paths.get(configuration.stateDir), Paths.get(configuration.skillsDir), http);
    }

    /**
     * The seam a test drives: a temp library and a stub client, because
     * {@link FordismConfiguration} reads the environment and the real client needs GitHub.
     */
    public SkillPluginStore(Path stateDir, Path skillsRoot, HttpClient http) {
        this.file = stateDir.resolve("skill-plugins.json");
        this.skillsRoot = skillsRoot;
        this.http = http;
        load();
    }

    public synchronized List<SkillPlugin> list() {
        return new ArrayList<>(plugins.values());
    }

    /**
     * Register a plugin and pull it in. A failed first sync leaves the entry with its error, so it
     * can be retried with Sync — but a URL that can never resolve is refused outright rather than
     * persisted, because there is nothing to retry and the row would sit in the registry forever
     * under a folder name derived from whatever the URL ended in.
     */
    public SkillPlugin add(String url, String ref) throws IOException {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a git URL is required");
        }
        // Resolve before registering, not inside the first sync: this is the malformed-URL and
        // non-HTTPS check, and both are permanent.
        archiveUrl(trimmed, ref == null || ref.isBlank() ? "HEAD" : ref.trim());
        String name = folderName(trimmed);
        synchronized (this) {
            for (SkillPlugin existing : plugins.values()) {
                if (existing.name().equals(name)) {
                    throw new IllegalArgumentException("a plugin named " + name + " is already added");
                }
            }
        }
        // No plugin owns that folder, so any skill sitting there was hand-written — and a plugin
        // owns its folder outright: the first sync would replace it and removing the plugin would
        // delete it, both reported as success. Refuse instead of taking it over.
        //
        // Emptiness is the test, not existence. Deleting the last skill under `skills/` leaves the
        // empty `skills/` directory behind, and a leftover directory the UI does not even show must
        // not lock a plugin name out for good.
        if (holdsAnyFile(skillsRoot.resolve(name))) {
            throw new IllegalArgumentException("the library already has a \"" + name
                    + "\" folder — rename those skills, or the plugin would replace them");
        }
        SkillPlugin plugin = new SkillPlugin(UUID.randomUUID().toString(), name, trimmed,
                ref == null || ref.isBlank() ? "HEAD" : ref.trim(), "", "");
        put(plugin);
        return sync(plugin.id());
    }


    /** Whether a directory holds any file at all, at any depth. */
    private static boolean holdsAnyFile(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Files.exists(dir);
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        }
    }
    /** Re-pull a plugin: its folder is replaced wholesale, so an upstream deletion propagates. */
    public SkillPlugin sync(String id) throws IOException {
        SkillPlugin plugin = get(id);
        Path target = skillsRoot.resolve(plugin.name());
        Path staging = Files.createTempDirectory("fordism-plugin-");
        try {
            try (InputStream zip = download(plugin)) {
                SkillStore.extractZip(staging, zip);
            }
            List<Path> skills = skillFolders(staging);
            if (skills.isEmpty()) {
                throw new IllegalArgumentException("no SKILL.md found in " + plugin.url());
            }
            SkillStore.deleteTree(target);
            for (Path skill : skills) {
                SkillStore.copyTree(skill, target.resolve(skill.getFileName().toString()));
            }
            SkillPlugin synced = plugin.synced(Instant.now().toString());
            put(synced);
            Logger.info("skill plugin {} synced — {} skills", plugin.name(), skills.size());
            return synced;
        } catch (Exception e) {
            SkillPlugin failed = plugin.failed(e.getMessage() == null ? e.toString() : e.getMessage());
            put(failed);
            Logger.warn("skill plugin {} sync failed: {}", plugin.name(), failed.lastError());
            return failed;
        } finally {
            SkillStore.deleteTree(staging);
        }
    }

    /** Forget a plugin and delete the skills it installed. Nothing else in the library is touched. */
    public void remove(String id) throws IOException {
        SkillPlugin plugin = get(id);
        SkillStore.deleteTree(skillsRoot.resolve(plugin.name()));
        synchronized (this) {
            plugins.remove(id);
            save();
        }
    }

    private synchronized SkillPlugin get(String id) {
        SkillPlugin plugin = plugins.get(id);
        if (plugin == null) {
            throw new IllegalArgumentException("no such plugin");
        }
        return plugin;
    }

    private synchronized void put(SkillPlugin plugin) {
        plugins.put(plugin.id(), plugin);
        save();
    }

    private InputStream download(SkillPlugin plugin) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(archiveUrl(plugin.url(), plugin.ref())))
                .timeout(TIMEOUT).header("Accept", "application/zip").GET().build();
        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new IllegalArgumentException("the repo answered HTTP " + response.statusCode()
                        + " — check the URL, the ref, and that the repo is public");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }
    }

    /**
     * The zip URL for a repo. A GitHub repo (or {@code owner/repo}) resolves to its codeload
     * archive; anything already ending in {@code .zip} is taken as-is, which is what makes a CI
     * artifact or a self-hosted archive work without a GitHub-shaped URL.
     */
    static String archiveUrl(String url, String ref) {
        String cleaned = url.trim();
        if (cleaned.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            // https only: the javadoc promises it, and without the check a skill.write holder
            // could point the server at an http:// address on the network it is running in.
            if (!cleaned.toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new IllegalArgumentException("a direct .zip URL must be https");
            }
            return cleaned;
        }
        String path = cleaned
                .replaceFirst("^git@github[.]com:", "")
                .replaceFirst("^https?://github[.]com/", "")
                .replaceFirst("[.]git$", "")
                .replaceAll("^/+|/+$", "");
        if (path.isEmpty() || path.split("/").length != 2) {
            throw new IllegalArgumentException(
                    "expected a GitHub repo (owner/repo or its https URL) or a direct .zip URL");
        }
        return "https://codeload.github.com/" + path + "/zip/" + ref;
    }

    /**
     * {@code <plugin>} in the library — the repo name, with anything path-unsafe dropped.
     *
     * <p>Normally that is the URL's last segment, which is the repo. A GitHub archive URL ends in
     * the <em>ref</em> instead ({@code …/skills/archive/refs/heads/main.zip}), so the last segment
     * would install 19 skills under {@code main/}. That URL is what GitHub's own "Download ZIP"
     * button hands out, so it is the likeliest direct zip anyone pastes; the repo is read out of
     * the path instead.
     */
    static String folderName(String url) {
        String cleaned = url.trim().replaceFirst("[.]zip$", "").replaceFirst("[.]git$", "")
                .replaceAll("/+$", "");
        Matcher archive = GITHUB_ARCHIVE.matcher(cleaned);
        if (archive.matches()) {
            cleaned = archive.group(1);
        }
        String last = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        last = last.substring(last.lastIndexOf(':') + 1);
        String safe = last.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^[.-]+", "");
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("could not read a plugin name from " + url);
        }
        return safe;
    }

    /**
     * The skill folders inside an unpacked archive. A GitHub zip wraps everything in one
     * {@code <repo>-<sha>/} directory, and a Claude Code plugin keeps its skills under
     * {@code skills/} — both are unwrapped here so the library gets {@code <plugin>/<skill>} rather
     * than the repo's own scaffolding.
     */
    private static List<Path> skillFolders(Path staging) throws IOException {
        Path root = onlyChildDirectory(staging);
        Path skillsDir = root.resolve("skills");
        Path base = Files.isDirectory(skillsDir) ? skillsDir : root;
        List<Path> out = new ArrayList<>();
        if (Files.isRegularFile(base.resolve("SKILL.md"))) {
            out.add(base);
            return out;
        }
        try (Stream<Path> children = Files.list(base)) {
            for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                if (Files.isRegularFile(child.resolve("SKILL.md"))) {
                    out.add(child);
                }
            }
        }
        return out;
    }

    private static Path onlyChildDirectory(Path dir) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            List<Path> found = children.toList();
            if (found.size() == 1 && Files.isDirectory(found.get(0))) {
                return found.get(0);
            }
        }
        return dir;
    }


    private void load() {
        try {
            if (!Files.exists(file)) {
                return;
            }
            State state = GSON.fromJson(Files.readString(file), State.class);
            if (state != null && state.plugins != null) {
                for (SkillPlugin plugin : state.plugins) {
                    plugins.put(plugin.id(), plugin);
                }
            }
        } catch (Exception e) {
            Logger.warn(e, "skill-plugins load failed");
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            State state = new State();
            state.plugins = new ArrayList<>(plugins.values());
            Files.writeString(file, GSON.toJson(state));
        } catch (Exception e) {
            Logger.error(e, "skill-plugins save failed");
        }
    }

    private static final class State {
        List<SkillPlugin> plugins;
    }
}
