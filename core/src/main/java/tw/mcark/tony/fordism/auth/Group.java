package tw.mcark.tony.fordism.auth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A named set of users and the grant patterns they hold through it. Permissions attach to groups
 * only — never to a user directly — so "what can this person do" is always answerable by reading
 * the groups page, and revoking a capability is one edit rather than an audit.
 */
public record Group(String id, String name, List<String> memberUserIds, List<String> grants) {

    public Group {
        name = name == null ? "" : name.trim();
        memberUserIds = distinct(memberUserIds);
        grants = distinct(grants);
    }

    /** A group as it looks before the store assigns it an id. */
    public static Group named(String name, List<String> grants) {
        return new Group(null, name, List.of(), grants);
    }

    public boolean hasMember(String userId) {
        return userId != null && memberUserIds.contains(userId);
    }

    /** Whether this group hands its members every permission there is. */
    public boolean grantsEverything() {
        return grants.contains(PermissionMatcher.EVERYTHING);
    }

    public Group withId(String assigned) {
        return new Group(assigned, name, memberUserIds, grants);
    }

    public Group withMember(String userId) {
        if (hasMember(userId)) {
            return this;
        }
        List<String> members = new ArrayList<>(memberUserIds);
        members.add(userId);
        return new Group(id, name, members, grants);
    }

    /** The same group with a departed user removed — how a user deletion tidies up after itself. */
    public Group withoutMember(String userId) {
        if (!hasMember(userId)) {
            return this;
        }
        List<String> members = new ArrayList<>(memberUserIds);
        members.remove(userId);
        return new Group(id, name, members, grants);
    }

    private static List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                kept.add(value.trim());
            }
        }
        return List.copyOf(kept);
    }
}
