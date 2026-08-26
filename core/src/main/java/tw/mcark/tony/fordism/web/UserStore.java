package tw.mcark.tony.fordism.web;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal per-user profile store, file-backed. One record per email. */
public final class UserStore {
    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, Map<String, Object>>>() {}.getType();
    private final Path file;
    private final Map<String, Map<String, Object>> users = new LinkedHashMap<>();

    public UserStore(String stateDir) {
        this.file = Paths.get(stateDir, "users.json");
        try {
            if (Files.exists(file)) {
                Map<String, Map<String, Object>> loaded = GSON.fromJson(Files.readString(file), TYPE);
                if (loaded != null) {
                    users.putAll(loaded);
                }
            }
        } catch (Exception ignored) {
            // start empty
        }
    }

    /** Create the profile on first login, stamp lastSeen; return the profile. */
    public synchronized Map<String, Object> touch(String email) {
        String now = Instant.now().toString();
        Map<String, Object> u = users.get(email);
        if (u == null) {
            u = new LinkedHashMap<>();
            u.put("email", email);
            u.put("displayName", email.contains("@") ? email.substring(0, email.indexOf('@')) : email);
            u.put("firstSeen", now);
            users.put(email, u);
        }
        u.put("lastSeen", now);
        save();
        return u;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(users, TYPE));
        } catch (Exception ignored) {
            // best effort
        }
    }
}
