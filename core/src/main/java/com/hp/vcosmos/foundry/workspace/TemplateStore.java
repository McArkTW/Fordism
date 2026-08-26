package com.hp.vcosmos.foundry.workspace;

import com.google.gson.Gson;
import com.hp.vcosmos.foundry.config.FoundryConfiguration;
import com.hp.vcosmos.foundry.skill.SkillStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.tinylog.Logger;
import java.util.Optional;

/**
 * Templates as UUID-keyed folders: {@code <root>/<uuid>/manifest.json} = {@code {id, name,
 * agentProfile, model, skills[], memory}}. Identity is the UUID folder (so a rename is an in-place
 * field edit, never a new record); {@code name} is a mutable, unique label. Legacy name-keyed
 * folders (and pre-manifest {@code skills/skill.md}+{@code memory/seed.md} templates) are migrated
 * to UUID folders on boot. Dispatch resolves a workflow step's template by NAME ({@link #getByName}).
 */
public final class TemplateStore {
    private static final Gson GSON = new Gson();
    private final FoundryConfiguration configuration;
    private final SkillStore skills;

    public TemplateStore(FoundryConfiguration configuration, SkillStore skills) {
        this.configuration = configuration;
        this.skills = skills;
    }

    /**
     * Bring the templates directory up to date, then make sure the built-in one exists.
     *
     * <p>Called from the composition root, not the constructor: this moves folders and writes
     * manifests, and construction should not touch the disk — a test or a tool that only wants to
     * read a template should not migrate the instance by instantiating a store.
     */
    public void migrateAndSeed() {
        migrate();
        ensureGeneric();
    }

    /** The built-in "run bare on the default backend" template that demo workflows reference. */
    private void ensureGeneric() {
        try {
            if (getByName("generic").isEmpty()) {
                create(new AgentTemplate(null, "generic", "", "", List.of(), List.of(), ""));
                Logger.info("seeded default 'generic' template");
            }
        } catch (IOException | RuntimeException e) {
            Logger.warn("could not seed 'generic' template: {}", e.getMessage());
        }
    }

    private Path root() {
        return Paths.get(configuration.templatesRoot);
    }

    private Path dir(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("invalid template id");
        }
        return root().resolve(id);
    }

    /** List — id + name per template. */
    public List<TemplateView> list() {
        List<TemplateView> out = new ArrayList<>();
        for (AgentTemplate template : all()) {
            out.add(TemplateView.summary(template));
        }
        out.sort(Comparator.comparing(TemplateView::name));
        return out;
    }

    /** Public view by id. */
    public TemplateView read(String id) {
        AgentTemplate template = get(id).orElse(null);
        if (template == null) {
            return new TemplateView(id, "", false, "", "", List.of(), List.of(), "");
        }
        return new TemplateView(id, nz(template.name()), true, nz(template.agentProfile()),
                nz(template.model()), template.skills(), template.credentials(),
                nz(template.instructions()));
    }

    /** Resolved template by id. */
    public Optional<AgentTemplate> get(String id) {
        Path manifestFile = dir(id).resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        try {
            AgentTemplate loaded = GSON.fromJson(Files.readString(manifestFile), AgentTemplate.class);
            if (loaded == null) {
                return Optional.empty();
            }
            return Optional.of(new AgentTemplate(id, loaded.name(), loaded.agentProfile(), loaded.model(),
                    loaded.skills(), loaded.credentials(), loaded.instructions()));
        } catch (IOException | RuntimeException e) {
            Logger.warn("unreadable template manifest {}: {}", manifestFile, e.getMessage());
            return Optional.empty();
        }
    }

    /** Resolved template by display name — how a workflow step references one. */
    public Optional<AgentTemplate> getByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(template -> name.equals(template.name())).findFirst();
    }

    /** All templates. */
    public List<AgentTemplate> all() {
        List<AgentTemplate> out = new ArrayList<>();
        Path root = root();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : (Iterable<Path>) entries.filter(Files::isDirectory)::iterator) {
                get(entry.getFileName().toString()).ifPresent(out::add);
            }
        } catch (IOException e) {
            Logger.warn("could not list templates in {}: {}", root, e.getMessage());
        }
        return out;
    }

    /**
     * Create a new template under a fresh UUID. Rejects a duplicate name. Returns the new id.
     *
     * <p>Takes the record rather than its six fields spread out: the id on the argument is ignored,
     * because identity is this store's to assign.
     */
    public String create(AgentTemplate template) throws IOException {
        requireUniqueName(template.name(), null);
        String id = UUID.randomUUID().toString();
        Files.createDirectories(dir(id));
        writeManifest(normalised(id, template));
        return id;
    }

    /** Update in place by id — including a rename (id/folder never change). Rejects a duplicate name. */
    public void update(String id, AgentTemplate template) throws IOException {
        if (get(id).isEmpty()) {
            throw new IllegalArgumentException("unknown template");
        }
        requireUniqueName(template.name(), id);
        writeManifest(normalised(id, template));
    }

    /** The record as it goes to disk: this store's id, trimmed name, no nulls. */
    private static AgentTemplate normalised(String id, AgentTemplate template) {
        return new AgentTemplate(id, clean(template.name()), nz(template.agentProfile()), nz(template.model()),
                template.skills(), template.credentials(), nz(template.instructions()));
    }

    public void delete(String id) throws IOException {
        Path dir = dir(id);
        if (!Files.exists(dir)) {
            return;
        }
        deleteTree(dir);
    }

    /** Cascade a profile rename: retarget every template that referenced the old profile name. */
    public void retargetProfile(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName)) {
            return;
        }
        for (AgentTemplate template : all()) {
            if (oldName.equals(template.agentProfile())) {
                try {
                    writeManifest(new AgentTemplate(template.id(), template.name(), newName,
                            template.model(), template.skills(), template.credentials(), template.instructions()));
                } catch (IOException e) {
                    Logger.warn("retargetProfile failed for template {}: {}", template.id(), e.getMessage());
                }
            }
        }
    }

    /** Stage a template into a workspace: its named library skills and its instructions. */
    public void stageInto(String templateName, Path workspace) throws IOException {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);

        AgentTemplate manifest = getByName(templateName).orElse(null);
        if (manifest == null) {
            return;
        }
        Path templateDir = dir(manifest.id());
        for (String sub : new String[]{"skills", "memory"}) {
            Path source = templateDir.resolve(sub);
            if (Files.isDirectory(source)) {
                copyDir(source, workspace.resolve(sub));
            }
        }
        for (String skillName : manifest.skills()) {
            skills.copyInto(skillName, skillsDir);
        }
        if (manifest.instructions() != null && !manifest.instructions().isBlank()) {
            // The entrypoint prepends this to the prompt, so it reaches claude-code and qwen-code
            // alike rather than depending on one CLI's habit of auto-loading a file it happens to find.
            Files.writeString(workspace.resolve("instructions.md"), manifest.instructions());
        }
    }

    /** Migrate legacy name-keyed folders → UUID folders (synthesizing a manifest where needed). */
    private void migrate() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : (Iterable<Path>) entries.filter(Files::isDirectory).toList()) {
                try {
                    migrateOne(entry);
                } catch (IOException | RuntimeException e) {
                    Logger.warn("template migrate skip {}: {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            Logger.warn(e, "template migrate failed");
        }
    }

    private void migrateOne(Path folder) throws IOException {
        String folderName = folder.getFileName().toString();
        Path manifestFile = folder.resolve("manifest.json");
        AgentTemplate loaded = null;
        if (Files.isRegularFile(manifestFile)) {
            try {
                loaded = GSON.fromJson(Files.readString(manifestFile), AgentTemplate.class);
            } catch (RuntimeException e) {
                loaded = null;
            }
        }
        if (loaded != null && folderName.equals(loaded.id())) {
            return;   // already UUID-keyed
        }
        String id = (loaded != null && loaded.id() != null && !loaded.id().isBlank()) ? loaded.id() : UUID.randomUUID().toString();
        String name = (loaded != null && loaded.name() != null && !loaded.name().isBlank()) ? loaded.name() : folderName;
        String agentProfile = loaded == null ? "" : nz(loaded.agentProfile());
        String model = loaded == null ? "" : nz(loaded.model());
        List<String> skillNames = loaded == null ? List.of() : loaded.skills();
        List<String> credentialKeys = loaded == null ? List.of() : loaded.credentials();
        String instructions = loaded == null ? readOrEmpty(folder.resolve("memory/seed.md")) : nz(loaded.instructions());

        Path target = root().resolve(id);
        if (!folder.equals(target)) {
            Files.move(folder, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(target);
        Files.writeString(target.resolve("manifest.json"),
                GSON.toJson(new AgentTemplate(id, name, agentProfile, model, skillNames, credentialKeys, instructions)));
        Logger.info("migrated template {} -> {}", folderName, id);
    }

    private void writeManifest(AgentTemplate template) throws IOException {
        Files.createDirectories(dir(template.id()));
        Files.writeString(dir(template.id()).resolve("manifest.json"), GSON.toJson(template));
    }

    private void requireUniqueName(String name, String exceptId) {
        String trimmed = clean(name);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name required");
        }
        for (AgentTemplate template : all()) {
            if (trimmed.equals(template.name()) && !template.id().equals(exceptId)) {
                throw new IllegalArgumentException("a template named \"" + trimmed + "\" already exists");
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static void copyDir(Path source, Path destination) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
