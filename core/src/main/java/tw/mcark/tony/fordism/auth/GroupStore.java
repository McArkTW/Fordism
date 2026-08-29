package tw.mcark.tony.fordism.auth;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.tinylog.Logger;

/**
 * Groups, as one JSON array at {@code <stateDir>/groups.json} — the same file pattern, failure
 * behaviour and read-per-call reasoning as {@link UserStore}.
 *
 * <p>This is also where "what may this person do" is answered, because a user's permissions are
 * nothing but the union of the grants on the groups holding them.
 */
public final class GroupStore {
    private static final Type TYPE = new TypeToken<List<Group>>() {}.getType();

    private final JsonRecordFile<Group> file;

    public GroupStore(Path stateDir) {
        this.file = new JsonRecordFile<>(stateDir.resolve("groups.json"), TYPE);
    }

    public synchronized List<Group> all() {
        return file.read();
    }

    public synchronized Optional<Group> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(group -> id.equals(group.id())).findFirst();
    }

    public synchronized Optional<Group> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return all().stream().filter(group -> wanted.equals(group.name())).findFirst();
    }

    /** The groups this user belongs to. */
    public synchronized List<Group> forUser(String userId) {
        List<Group> out = new ArrayList<>();
        for (Group group : all()) {
            if (group.hasMember(userId)) {
                out.add(group);
            }
        }
        return out;
    }

    /** The names of the groups this user belongs to — what {@code /api/auth/me} shows. */
    public synchronized List<String> groupNamesFor(String userId) {
        List<String> names = new ArrayList<>();
        for (Group group : forUser(userId)) {
            names.add(group.name());
        }
        return names;
    }

    /** This user's effective permissions: the union of the grants across every group holding them. */
    public synchronized Set<String> grantsFor(String userId) {
        Set<String> grants = new LinkedHashSet<>();
        for (Group group : forUser(userId)) {
            grants.addAll(group.grants());
        }
        return grants;
    }

    /** Whether this user's effective permissions cover what a route asked for. */
    public synchronized boolean allows(String userId, Permission required) {
        return PermissionMatcher.anyMatches(grantsFor(userId), required);
    }

    public synchronized Group create(Group draft) {
        requireName(draft.name());
        List<Group> groups = all();
        requireNameFree(groups, draft.name(), null);
        Group stored = draft.withId(UUID.randomUUID().toString());
        groups.add(stored);
        file.write(groups);
        return stored;
    }

    public synchronized Group update(Group edited) {
        requireName(edited.name());
        List<Group> groups = all();
        int at = indexOf(groups, edited.id());
        requireNameFree(groups, edited.name(), edited.id());
        groups.set(at, edited);
        file.write(groups);
        return edited;
    }

    public synchronized void delete(String id) {
        List<Group> groups = all();
        groups.remove(indexOf(groups, id));
        file.write(groups);
    }

    /** Write a whole proposed group list — how a guarded, multi-group edit commits. */
    public synchronized void replaceAll(List<Group> groups) {
        file.write(new ArrayList<>(groups));
    }

    /**
     * Create this group if no group of that name exists; otherwise leave the existing one exactly
     * as it is.
     *
     * <p>Called from the composition root on every boot. Create-if-missing rather than
     * create-or-update on purpose: an operator who narrowed {@code operators} should not find it
     * widened again by the next restart.
     */
    public synchronized void seed(Group group) {
        if (findByName(group.name()).isPresent()) {
            return;
        }
        create(group);
        Logger.info("seeded group {} granting {}", group.name(), group.grants());
    }

    /**
     * Whether this proposed world still lets somebody administer it: at least one group that grants
     * {@code *} and holds at least one account that still exists.
     *
     * <p>Takes the proposed lists rather than reading the current ones, so an edit can be refused
     * before it is written instead of rolled back after.
     */
    public static boolean fullAccessSurvives(List<User> users, List<Group> groups) {
        for (Group group : groups) {
            if (!group.grantsEverything()) {
                continue;
            }
            for (User user : users) {
                if (group.hasMember(user.id())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int indexOf(List<Group> groups, String id) {
        for (int index = 0; index < groups.size(); index++) {
            if (groups.get(index).id() != null && groups.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("no group with id " + id);
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a group name is required");
        }
    }

    private static void requireNameFree(List<Group> groups, String name, String exceptId) {
        for (Group group : groups) {
            if (group.name().equals(name) && !group.id().equals(exceptId)) {
                throw new IllegalArgumentException("a group named \"" + name + "\" already exists");
            }
        }
    }
}
