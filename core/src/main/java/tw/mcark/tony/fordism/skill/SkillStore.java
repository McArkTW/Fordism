package tw.mcark.tony.fordism.skill;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.google.gson.Gson;
import org.tinylog.Logger;

/**
 * The skills library: each skill is a folder addressed by a namespaced name (namespaced,
 * e.g. {@code access/github}) containing a {@code SKILL.md} plus optional files. Content is a
 * read-only mirror of an external skills repo; per-skill enable/disable is Fordism-local ({@link SkillState}).
 */
public final class SkillStore {
    private static final Gson GSON = new Gson();

    /**
     * How much of one file the API will hand back. A skill folder can hold a fixture far larger
     * than anything worth reading in a browser, and the whole file would otherwise be held in
     * memory twice — as bytes and as a JSON string — for every concurrent reader.
     */
    static final int MAX_FILE_BYTES = 256 * 1024;

    private final Path root;
    private final SkillState state;

    public SkillStore(FordismConfiguration configuration, SkillState state) {
        this(Paths.get(configuration.skillsDir), state);
    }

    /** The seam a test points at a temp directory — {@link FordismConfiguration} reads the environment. */
    public SkillStore(Path root, SkillState state) {
        this.root = root;
        this.state = state;
    }

    private Path root() {
        return root;
    }

    private Path resolve(String name) {
        if (name == null || name.isBlank() || name.contains("..") || name.startsWith("/")) {
            throw new IllegalArgumentException("invalid skill name");
        }
        Path root = root().toAbsolutePath().normalize();
        Path dir = root.resolve(name).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("invalid skill name");
        }
        return dir;
    }

    /**
     * All skills (dirs holding a SKILL.md), by relative namespaced name.
     *
     * <p>Each row carries the file count and the newest mtime in the folder, because the page sorts
     * on both and a second request per row to learn them would be one request per skill.
     */
    public List<SkillView> list() {
        List<SkillView> out = new ArrayList<>();
        Path root = root();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path skillMd : (Iterable<Path>) walk.filter(p -> p.getFileName().toString().equals("SKILL.md"))::iterator) {
                Path dir = skillMd.getParent();
                String name = root.relativize(dir).toString().replace('\\', '/');
                Folder folder = measure(dir);
                out.add(SkillView.summary(name, description(skillMd), !state.isDisabled(name),
                        folder.files(), folder.updatedAt()));
            }
        } catch (IOException e) {
            Logger.warn("could not list skills in {}: {}", root, e.getMessage());
        }
        out.sort(Comparator.comparing(SkillView::name));
        return out;
    }

    /** How many files a skill folder holds and when any of them last changed. */
    private record Folder(int files, String updatedAt) {}

    /**
     * The newest mtime in the folder, not {@code SKILL.md}'s: a skill whose script was edited but
     * whose contract was not has still changed, and a column that said otherwise would be worse
     * than no column.
     */
    private static Folder measure(Path dir) {
        int files = 0;
        FileTime newest = null;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                files++;
                try {
                    FileTime modified = Files.getLastModifiedTime(path);
                    if (newest == null || modified.compareTo(newest) > 0) {
                        newest = modified;
                    }
                } catch (IOException e) {
                    // One unreadable file must not cost the whole row its count.
                    Logger.debug("could not stat {}: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            Logger.warn("could not measure skill folder {}: {}", dir, e.getMessage());
        }
        return new Folder(files, newest == null ? "" : newest.toInstant().toString());
    }

    public SkillView read(String name) throws IOException {
        Path dir = resolve(name);
        Path skillMd = dir.resolve("SKILL.md");
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    files.add(dir.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        files.sort(Comparator.naturalOrder());
        boolean exists = Files.isRegularFile(skillMd);
        Folder folder = measure(dir);
        return new SkillView(name, description(skillMd), !state.isDisabled(name), null,
                folder.files(), folder.updatedAt(), exists,
                exists ? Files.readString(skillMd) : "", files);
    }

    /**
     * One file inside a skill, by its path relative to the skill folder.
     *
     * <p>{@code relative} is attacker-chosen — it comes back from a list the browser was given, but
     * nothing stops a caller sending {@code ../../etc/passwd} instead — so it is resolved through
     * {@link #safeChild}, the same guard the zip extractor and the folder upload use, and never
     * through a bare {@code dir.resolve}.
     */
    public SkillFile readFile(String name, String relative) throws IOException {
        Path dir = resolve(name);
        Path file = safeChild(dir, relative);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no such file in " + name);
        }
        long size = Files.size(file);
        byte[] head = head(file);
        for (byte b : head) {
            if (b == 0) {
                return new SkillFile(relative, size, true, false, "");
            }
        }
        boolean truncated = size > MAX_FILE_BYTES;
        String content = truncated
                ? new String(head, StandardCharsets.UTF_8)
                : Files.readString(file, StandardCharsets.UTF_8);
        return new SkillFile(relative, size, false, truncated, content);
    }

    /** The first {@link #MAX_FILE_BYTES}, which is both the binary probe and the truncated body. */
    private static byte[] head(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(MAX_FILE_BYTES);
        }
    }

    /**
     * Delete every named skill, and report per name rather than stopping at the first failure.
     *
     * <p>A caller who selected twenty skills needs to know which of them went, not that "something"
     * failed: aborting halfway would leave the library in a state the page cannot describe. Names
     * the store refuses are collected the same way, so one bad name in a selection cannot cost the
     * other nineteen their delete.
     *
     * @return the reason each failed name failed, empty when every one went
     */
    public Map<String, String> deleteAll(List<String> names) {
        Map<String, String> failed = new LinkedHashMap<>();
        for (String name : names == null ? List.<String>of() : names) {
            try {
                delete(name);
            } catch (IllegalArgumentException | IOException e) {
                failed.put(name, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        return failed;
    }

    public void write(String name, String content) throws IOException {
        Path dir = resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content == null ? "" : content);
    }

    public void delete(String name) throws IOException {
        deleteTree(resolve(name));
    }

    /**
     * Replace a skill's folder with an uploaded one: the browser sends each file's path relative
     * to the picked folder alongside its content.
     *
     * <p>The upload is assembled in a staging directory and only swapped in once it is known to be
     * a skill. Clearing the folder first — the obvious way to make a re-upload drop a file the user
     * deleted upstream — meant a refused upload took the skill it was replacing with it: the caller
     * got a correct "must contain SKILL.md" and an empty library. Nothing the user already has is
     * deleted until there is something valid to put in its place.
     */
    public void writeFolder(String name, List<String> paths, List<InputStream> contents) throws IOException {
        if (paths.size() != contents.size()) {
            throw new IllegalArgumentException("each file needs a path");
        }
        Path dir = resolve(name);
        Path staging = Files.createTempDirectory("fordism-skill-");
        try {
            for (int i = 0; i < paths.size(); i++) {
                Path target = safeChild(staging, paths.get(i));
                if (target == null) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream os = Files.newOutputStream(target)) {
                    contents.get(i).transferTo(os);
                }
            }
            if (!Files.isRegularFile(staging.resolve("SKILL.md"))) {
                throw new IllegalArgumentException("the folder must contain SKILL.md");
            }
            deleteTree(dir);
            copyTree(staging, dir);
        } finally {
            deleteTree(staging);
        }
    }

    /** Copy a whole directory tree, creating {@code to} and every parent it needs. */
    public static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * {@code dir/relative}, or null when the entry escapes {@code dir}. A zip entry and a browser
     * upload both carry an attacker-chosen path, so neither is resolved without this.
     */
    public static Path safeChild(Path dir, String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path target = dir.resolve(relative.replace('\\', '/')).normalize();
        return target.startsWith(dir) && !target.equals(dir) ? target : null;
    }

    /** Delete a directory and everything under it. */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Extract a zip into {@code dir}, skipping any entry that escapes it. */
    public static void extractZip(Path dir, InputStream zipStream) throws IOException {
        Files.createDirectories(dir);
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = safeChild(dir, entry.getName());
                if (target == null) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream os = Files.newOutputStream(target)) {
                        zis.transferTo(os);
                    }
                }
            }
        }
    }

    /** Include a skill in future runs again. */
    public void enable(String name) {
        state.enable(name);
    }

    /** Keep a skill out of future runs; staging skips it even when a template names it. */
    public void disable(String name) {
        state.disable(name);
    }

    /** Source metadata for the mirror: {@code {repo, tag, syncedAt}} from {@code <skillsDir>/.source.json}. */
    public Map<String, Object> source() {
        Path file = root().resolve(".source.json");
        try {
            if (Files.isRegularFile(file)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = GSON.fromJson(Files.readString(file), Map.class);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return new LinkedHashMap<>();
    }

    /** Copy a skill's folder into a workspace's skills dir. DISABLED skills are skipped (excluded from the run). */
    public void copyInto(String name, Path destSkillsDir) throws IOException {
        if (state.isDisabled(name)) {
            Logger.info("skill {} is disabled — skipped for this run", name);
            return;
        }
        Path dir = resolve(name);
        if (!Files.isDirectory(dir)) {
            Logger.warn("skill {} is named by a template but is not in the library — skipped", name);
            return;
        }
        Path dest = destSkillsDir.resolve(name.replace('/', '_'));
        Files.createDirectories(dest);
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dest.resolve(dir.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * A YAML block-scalar indicator — {@code >}, {@code |}, and their chomping/indent variants.
     * The value is not on this line; it is the indented block underneath.
     */
    private static final java.util.regex.Pattern BLOCK_SCALAR =
            java.util.regex.Pattern.compile("[>|][+-]?[0-9]*");

    /**
     * The block scalar starting at {@code from}, folded to one line: every indented line until the
     * block ends, joined by spaces. Both {@code >} and {@code |} fold here — this is a one-line
     * summary for a list row, so the literal style's newlines would only be whitespace anyway.
     */
    private static String folded(List<String> lines, int from) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            // The block ends at the frontmatter fence, at a blank line followed by an unindented
            // one, or at the next key — anything that is not indented under `description:`.
            if (trimmed.equals("---")) {
                break;
            }
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                break;
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(trimmed);
        }
        return out.toString();
    }

    /** Prefer the YAML frontmatter {@code description:}; else the first heading/prose line. */
    private static String description(Path skillMd) {
        try {
            if (!Files.isRegularFile(skillMd)) {
                return "";
            }
            List<String> lines = Files.readAllLines(skillMd);
            // frontmatter description:
            if (!lines.isEmpty() && lines.get(0).strip().equals("---")) {
                for (int i = 1; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.equals("---")) {
                        break;
                    }
                    if (line.toLowerCase().startsWith("description:")) {
                        String value = line.substring("description:".length()).strip();
                        // `description: >` or `|-` is a YAML block scalar: the text is on the
                        // following, more-indented lines. Taking the indicator as the value put a
                        // literal ">" in the list where a sentence belonged.
                        if (BLOCK_SCALAR.matcher(value).matches()) {
                            return folded(lines, i + 1);
                        }
                        return value.replaceAll("^[\"']|[\"']$", "");
                    }
                }
            }
            // else first non-frontmatter, non-blank line
            boolean inFront = !lines.isEmpty() && lines.get(0).strip().equals("---");
            for (int i = inFront ? 1 : 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).strip();
                if (inFront && trimmed.equals("---")) {
                    inFront = false;
                    continue;
                }
                if (inFront || trimmed.isEmpty()) {
                    continue;
                }
                return trimmed.replaceFirst("^#+\\s*", "");
            }
        } catch (IOException e) {
            // ignore
        }
        return "";
    }
}
