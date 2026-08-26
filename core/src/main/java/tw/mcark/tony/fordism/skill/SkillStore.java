package tw.mcark.tony.fordism.skill;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
 * The skills library: each skill is a folder addressed by a namespaced name (tds-skills style,
 * e.g. {@code access/github}) containing a {@code SKILL.md} plus optional files. Content is a
 * read-only mirror of the tds-skills repo; per-skill enable/disable is Fordism-local ({@link SkillState}).
 */
public final class SkillStore {
    private static final Gson GSON = new Gson();
    private final FordismConfiguration configuration;
    private final SkillState state;

    public SkillStore(FordismConfiguration configuration, SkillState state) {
        this.configuration = configuration;
        this.state = state;
    }

    private Path root() {
        return Paths.get(configuration.skillsDir);
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

    /** All skills (dirs holding a SKILL.md), by relative namespaced name. */
    public List<SkillView> list() {
        List<SkillView> out = new ArrayList<>();
        Path root = root();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path skillMd : (Iterable<Path>) walk.filter(p -> p.getFileName().toString().equals("SKILL.md"))::iterator) {
                String name = root.relativize(skillMd.getParent()).toString().replace('\\', '/');
                out.add(SkillView.summary(name, description(skillMd), !state.isDisabled(name)));
            }
        } catch (IOException e) {
            Logger.warn("could not list skills in {}: {}", root, e.getMessage());
        }
        out.sort(Comparator.comparing(SkillView::name));
        return out;
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
        boolean exists = Files.isRegularFile(skillMd);
        return new SkillView(name, description(skillMd), !state.isDisabled(name), exists,
                exists ? Files.readString(skillMd) : "", files);
    }

    public void write(String name, String content) throws IOException {
        Path dir = resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content == null ? "" : content);
    }

    public void delete(String name) throws IOException {
        Path dir = resolve(name);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Extract an uploaded skill.zip into the skill dir (must contain SKILL.md; zip-slip guarded). */
    public void upload(String name, InputStream zipStream) throws IOException {
        Path dir = resolve(name);
        Files.createDirectories(dir);
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = dir.resolve(entry.getName()).normalize();
                if (!target.startsWith(dir)) {
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
        if (!Files.isRegularFile(dir.resolve("SKILL.md"))) {
            throw new IllegalArgumentException("skill.zip must contain SKILL.md");
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
                        return line.substring("description:".length()).strip().replaceAll("^[\"']|[\"']$", "");
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
