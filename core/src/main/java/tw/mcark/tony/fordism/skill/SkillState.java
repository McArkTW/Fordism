package tw.mcark.tony.fordism.skill;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.tinylog.Logger;

/**
 * Fordism-local skill state: which skills are DISABLED (excluded from future runs). Kept separate
 * from the skill content, which is a read-only mirror of an external skills repo. Persisted to
 * {@code <stateDir>/skills-state.json} so it survives redeploy.
 */
public final class SkillState {
    private static final Gson GSON = new Gson();
    private final Path file;
    private final Set<String> disabled = new LinkedHashSet<>();

    public SkillState(FordismConfiguration configuration) {
        this(Paths.get(configuration.stateDir));
    }

    /** The seam a test points at a temp directory — {@link FordismConfiguration} reads the environment. */
    public SkillState(Path stateDir) {
        this.file = stateDir.resolve("skills-state.json");
        load();
    }

    public synchronized boolean isDisabled(String name) {
        return disabled.contains(name);
    }

    public synchronized void enable(String name) {
        if (named(name) && disabled.remove(name)) {
            save();
        }
    }

    public synchronized void disable(String name) {
        if (named(name) && disabled.add(name)) {
            save();
        }
    }

    private static boolean named(String name) {
        return name != null && !name.isBlank();
    }

    public synchronized Set<String> disabledNames() {
        return new LinkedHashSet<>(disabled);
    }

    private void load() {
        try {
            if (!Files.exists(file)) {
                return;
            }
            State state = GSON.fromJson(Files.readString(file), State.class);
            if (state != null && state.disabled != null) {
                disabled.addAll(state.disabled);
            }
        } catch (Exception e) {
            Logger.warn(e, "skills-state load failed");
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            State state = new State();
            state.disabled = new TreeSet<>(disabled);
            Files.writeString(file, GSON.toJson(state));
        } catch (Exception e) {
            Logger.error(e, "skills-state save failed");
        }
    }

    private static final class State {
        Set<String> disabled;
    }
}
