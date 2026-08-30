package tw.mcark.tony.fordism.auth;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The people who can sign in, as one JSON array at {@code <stateDir>/users.json}.
 *
 * <p>The file is the truth and is re-read on every call rather than cached. A Fordism instance has
 * operators, not users-at-scale, and a cache here would have to be invalidated by the bootstrap
 * endpoint, the OAuth callback and both user-editing endpoints — three file reads per request is a
 * cheaper price than one of those forgetting.
 *
 * <p>Nothing here is silent: a missing file is an empty install, but an unreadable one throws.
 */
public final class UserStore {
    private static final Type TYPE = new TypeToken<List<User>>() {}.getType();

    private final JsonRecordFile<User> file;

    public UserStore(Path stateDir) {
        this.file = new JsonRecordFile<>(stateDir.resolve("users.json"), TYPE);
    }

    /** Every account, in creation order. */
    public synchronized List<User> all() {
        return file.read();
    }

    /** True on a fresh install — the only state in which the admin bootstrap is open. */
    public synchronized boolean isEmpty() {
        return all().isEmpty();
    }

    public synchronized Optional<User> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(user -> id.equals(user.id())).findFirst();
    }

    public synchronized Optional<User> findByEmail(String email) {
        String wanted = User.normalizedEmail(email);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return all().stream().filter(user -> wanted.equals(user.email())).findFirst();
    }

    public synchronized Optional<User> findByIdentity(LinkedIdentity identity) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(user -> user.isLinkedTo(identity)).findFirst();
    }

    /** Store a new account under a fresh id. The id on the argument is ignored — identity is ours. */
    public synchronized User create(User draft) {
        requireEmail(draft);
        List<User> users = all();
        requireEmailFree(users, draft.email(), null);
        User stored = draft.withId(UUID.randomUUID().toString());
        users.add(stored);
        file.write(users);
        return stored;
    }

    /** Replace an existing account in place, keeping its id and its position in the file. */
    public synchronized User update(User edited) {
        requireEmail(edited);
        List<User> users = all();
        int at = indexOf(users, edited.id());
        requireEmailFree(users, edited.email(), edited.id());
        users.set(at, edited);
        file.write(users);
        return edited;
    }

    public synchronized void delete(String id) {
        List<User> users = all();
        users.remove(indexOf(users, id));
        file.write(users);
    }

    /** Write a whole proposed user list — the seam a guarded, multi-record edit commits through. */
    public synchronized void replaceAll(List<User> users) {
        file.write(new ArrayList<>(users));
    }

    private static int indexOf(List<User> users, String id) {
        for (int index = 0; index < users.size(); index++) {
            if (users.get(index).id() != null && users.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("no user with id " + id);
    }

    private static void requireEmail(User user) {
        if (user.email().isBlank() || user.email().indexOf('@') <= 0) {
            throw new IllegalArgumentException("an email address is required, got: \"" + user.email() + "\"");
        }
    }

    private static void requireEmailFree(List<User> users, String email, String exceptId) {
        for (User user : users) {
            if (user.email().equals(email) && !user.id().equals(exceptId)) {
                throw new IllegalArgumentException("an account already uses " + email);
            }
        }
    }
}
