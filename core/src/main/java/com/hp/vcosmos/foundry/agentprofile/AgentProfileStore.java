package com.hp.vcosmos.foundry.agentprofile;

import com.google.gson.Gson;
import com.hp.vcosmos.foundry.config.FoundryConfiguration;
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
 * Agent Profiles, one JSON file per profile at {@code <agentProfilesDir>/<uuid>.json}. Identity is
 * the UUID (so a rename is an in-place field edit, never a new record); {@code name} is a mutable,
 * unique label. API keys are stored on disk but stripped from every browser-facing view. Legacy
 * name-keyed files ({@code <name>.json}, no id) are migrated to UUID files on boot, and any records
 * left in the old {@code llm-sources/} sibling directory are moved into this store.
 */
public final class AgentProfileStore {
    private static final Gson GSON = new Gson();
    private final FoundryConfiguration configuration;

    public AgentProfileStore(FoundryConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Move any records left in the old {@code llm-sources/} directory into this store, then rewrite
     * legacy name-keyed files as UUID-keyed ones.
     *
     * <p>Called from the composition root, not the constructor — see {@code TemplateStore}.
     */
    public void migrateLegacyRecords() {
        migrateLegacyDir();
        migrate();
    }

    private Path root() {
        return Paths.get(configuration.agentProfilesDir);
    }

    private Path file(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("invalid profile id");
        }
        return root().resolve(id + ".json");
    }

    /** Public list — keys stripped, {@code hasKey} flag only. */
    public List<AgentProfileView> list() {
        List<AgentProfileView> out = new ArrayList<>();
        for (AgentProfile profile : all()) {
            out.add(AgentProfileView.of(profile));
        }
        out.sort(Comparator.comparing(AgentProfileView::name));
        return out;
    }

    /** Public single view by id — key stripped. */
    public AgentProfileView read(String id) {
        return get(id).map(AgentProfileView::of).map(AgentProfileView::found)
                .orElseGet(() -> new AgentProfileView(id, null, null, null, null, false, false));
    }

    /** Full record incl. key by id. Server-side only. */
    public Optional<AgentProfile> get(String id) {
        Path path = file(id);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            AgentProfile loaded = GSON.fromJson(Files.readString(path), AgentProfile.class);
            if (loaded == null) {
                return Optional.empty();
            }
            return Optional.of(new AgentProfile(id, loaded.name(), loaded.baseUrl(), loaded.apiKey(),
                    loaded.model(), loaded.tool()));
        } catch (IOException | RuntimeException e) {
            Logger.warn("unreadable agent-profile {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /** Full record incl. key by display name — how a template references one. */
    public Optional<AgentProfile> getByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(profile -> name.equals(profile.name())).findFirst();
    }

    /** All full records incl. keys. Server-side only. */
    public List<AgentProfile> all() {
        List<AgentProfile> out = new ArrayList<>();
        Path root = root();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path path : (Iterable<Path>) entries.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                get(path.getFileName().toString().replaceFirst("\\.json$", "")).ifPresent(out::add);
            }
        } catch (IOException e) {
            Logger.warn("could not list agent-profiles in {}: {}", root, e.getMessage());
        }
        return out;
    }

    /**
     * Create a new profile under a fresh UUID. Rejects a duplicate name. Returns the new id.
     *
     * <p>The id on the argument is ignored — identity is this store's to assign.
     */
    public String create(AgentProfile profile) throws IOException {
        requireUniqueName(profile.name(), null);
        String id = UUID.randomUUID().toString();
        write(normalised(id, profile, profile.apiKey()));
        return id;
    }

    /**
     * Update a profile in place by id — including a rename (the id never changes). A blank
     * {@code apiKey} preserves the stored key. Rejects a name already used by another profile.
     */
    public void update(String id, AgentProfile profile) throws IOException {
        AgentProfile existing = get(id).orElseThrow(() -> new IllegalArgumentException("unknown profile"));
        requireUniqueName(profile.name(), id);
        String key = profile.hasKey() ? profile.apiKey() : existing.apiKey();
        write(normalised(id, profile, key));
    }

    /** The record as it goes to disk: this store's id, trimmed fields, the key that survived. */
    private static AgentProfile normalised(String id, AgentProfile profile, String apiKey) {
        return new AgentProfile(id, clean(profile.name()), clean(profile.baseUrl()),
                apiKey == null ? "" : apiKey, clean(profile.model()), profile.tool());
    }

    public void delete(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    private void write(AgentProfile profile) throws IOException {
        Files.createDirectories(root());
        Files.writeString(file(profile.id()), GSON.toJson(profile));
    }

    private void requireUniqueName(String name, String exceptId) {
        String trimmed = clean(name);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name required");
        }
        for (AgentProfile profile : all()) {
            if (trimmed.equals(profile.name()) && !profile.id().equals(exceptId)) {
                throw new IllegalArgumentException("a profile named \"" + trimmed + "\" already exists");
            }
        }
    }

    /** One-time move of records left in the old {@code llm-sources/} sibling dir into this store. */
    private void migrateLegacyDir() {
        Path legacy = root().resolveSibling("llm-sources");
        if (!Files.isDirectory(legacy) || legacy.equals(root())) {
            return;
        }
        try {
            Files.createDirectories(root());
            try (Stream<Path> entries = Files.list(legacy)) {
                for (Path path : (Iterable<Path>) entries.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                    Path target = root().resolve(path.getFileName().toString());
                    if (!Files.exists(target)) {
                        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                        Logger.info("migrated agent-profile {} from legacy llm-sources/", path.getFileName());
                    }
                }
            }
        } catch (IOException e) {
            Logger.warn(e, "agent-profile legacy-dir migrate failed");
        }
    }

    /** One-time migration of legacy name-keyed files ({@code <name>.json}, no id) → {@code <uuid>.json}. */
    private void migrate() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path path : (Iterable<Path>) entries.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                try {
                    AgentProfile loaded = GSON.fromJson(Files.readString(path), AgentProfile.class);
                    if (loaded == null) {
                        continue;
                    }
                    String fileStem = path.getFileName().toString().replaceFirst("\\.json$", "");
                    if (loaded.id() != null && loaded.id().equals(fileStem)) {
                        continue;   // already id-keyed
                    }
                    String id = (loaded.id() == null || loaded.id().isBlank()) ? UUID.randomUUID().toString() : loaded.id();
                    // legacy files stored the name in the filename; keep it if the record lacks one
                    String name = (loaded.name() == null || loaded.name().isBlank()) ? fileStem : loaded.name();
                    AgentProfile migrated = new AgentProfile(id, name, loaded.baseUrl(), loaded.apiKey(), loaded.model(), loaded.tool());
                    Files.writeString(root.resolve(id + ".json"), GSON.toJson(migrated));
                    Files.deleteIfExists(path);
                    Logger.info("migrated agent-profile {} -> {}", fileStem, id);
                } catch (IOException | RuntimeException e) {
                    Logger.warn("agent-profile migrate skip {}: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            Logger.warn(e, "agent-profile migrate failed");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

}
