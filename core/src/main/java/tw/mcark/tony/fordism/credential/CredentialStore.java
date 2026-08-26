package tw.mcark.tony.fordism.credential;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.tinylog.Logger;
import java.util.Optional;

/**
 * Credentials the instance holds, one JSON file per credential at {@code <credentialsDir>/<KEY>.json}.
 * Identity is the environment-variable name, because that is what an agent and a template refer to.
 *
 * <p>On the host volume, so it survives restart and redeploy — unlike the in-memory vault that holds
 * what a human supplies when answering a question, which exists to unstick one run.
 *
 * <p>Values never leave this class toward a browser. {@link #list()} and {@link #read(String)}
 * return keys, notes and a {@code hasValue} flag; the only reader of a value is the launcher.
 */
public final class CredentialStore {
    private static final Gson GSON = new Gson();

    /** Environment-variable names only — the key becomes a filename and an --env-file key. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Path root;

    public CredentialStore(FordismConfiguration configuration) {
        this(Paths.get(configuration.credentialsDir));
    }

    /** The directory the credentials live in — the seam a test writes to a temp dir through. */
    public CredentialStore(Path root) {
        this.root = root;
    }

    private Path root() {
        return root;
    }

    private Path file(String key) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("not a valid environment-variable name: " + key);
        }
        return root().resolve(key + ".json");
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** Browser-facing list — values stripped. */
    public List<CredentialView> list() {
        List<CredentialView> out = new ArrayList<>();
        for (Credential credential : all()) {
            out.add(CredentialView.of(credential));
        }
        out.sort(Comparator.comparing(CredentialView::key));
        return out;
    }

    /** Browser-facing single view — value stripped, empty when there is no such credential. */
    public Optional<CredentialView> read(String key) {
        return get(key).map(CredentialView::of);
    }


    /** Full record including the value. Server-side only. */
    public Optional<Credential> get(String key) {
        if (!isValidKey(key)) {
            return Optional.empty();
        }
        Path path = file(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            Credential loaded = GSON.fromJson(Files.readString(path), Credential.class);
            if (loaded == null) {
                return Optional.empty();
            }
            return Optional.of(new Credential(key, loaded.value(), loaded.note(), loaded.updatedAt()));
        } catch (IOException | RuntimeException e) {
            Logger.warn("unreadable credential file {}", path);
            return Optional.empty();
        }
    }

    /** Every stored credential, values included. Server-side only. */
    public List<Credential> all() {
        List<Credential> out = new ArrayList<>();
        Path root = root();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path path : (Iterable<Path>) entries.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                get(path.getFileName().toString().replaceFirst("\\.json$", "")).ifPresent(out::add);
            }
        } catch (IOException e) {
            Logger.warn("could not list credentials in {}", root);
        }
        return out;
    }

    /** The environment for a task, from the keys its Agent Template declared. */
    public Map<String, String> values(List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        if (keys == null) {
            return out;
        }
        for (String key : keys) {
            get(key).filter(Credential::hasValue)
                    .ifPresent(credential -> out.put(credential.key(), credential.value()));
        }
        return out;
    }

    /**
     * Create or replace. A blank {@code value} on an existing record preserves the stored one, so
     * editing the note does not require re-typing the secret.
     */
    public void save(String key, String value, String note) throws IOException {
        String kept = value != null && !value.isBlank()
                ? value
                : get(key).map(Credential::value).orElse(value);
        if (kept != null && (kept.indexOf('\n') >= 0 || kept.indexOf('\r') >= 0)) {
            // The launcher's --env-file writer turns a newline into a space, which would corrupt a
            // token into one that fails authentication for reasons no log explains. Refuse instead.
            throw new IllegalArgumentException("value for " + key + " contains a line break");
        }
        Files.createDirectories(root());
        Path path = file(key);
        Files.writeString(path, GSON.toJson(new Credential(
                key, kept == null ? "" : kept, note == null ? "" : note.trim(), System.currentTimeMillis())));
        restrictPermissions(path);
        Logger.info("credential {} saved", key);
    }

    public void delete(String key) throws IOException {
        Files.deleteIfExists(file(key));
        Logger.info("credential {} deleted", key);
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // non-POSIX filesystem (dev on Windows) — the directory is already user-scoped
        }
    }
}
