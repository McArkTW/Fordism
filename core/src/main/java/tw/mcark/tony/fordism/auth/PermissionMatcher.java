package tw.mcark.tony.fordism.auth;

import java.util.Collection;

/**
 * Does a group's grant pattern cover the permission a route asked for?
 *
 * <p>Three rules, and deliberately no fourth:
 * <ul>
 *   <li>an exact match — {@code workflow.read} grants {@code workflow.read};
 *   <li>the bare {@code *} — everything;
 *   <li>a trailing {@code .*} — every descendant at any depth, so {@code run.*} covers
 *       {@code run.workspace.download} but {@code admin.*} does not cover {@code admin} itself
 *       (a node is not its own descendant) nor {@code administrator.x} (the boundary is a dot,
 *       not a character count).
 * </ul>
 *
 * <p>Wildcards are trailing-only. {@code *.read} matches nothing: a leading wildcard would let one
 * pattern reach across unrelated subtrees, which makes a grant impossible to read off a group page.
 * The exact rows this file must satisfy live in {@code src/test/resources/permission-matcher-vectors.json},
 * shared with the app's TypeScript matcher so the two cannot drift.
 */
public final class PermissionMatcher {

    /** The grant that covers every permission there is. */
    public static final String EVERYTHING = "*";

    private static final String SUBTREE_SUFFIX = ".*";
    private static final String SEPARATOR = ".";

    private PermissionMatcher() {}

    /** Whether {@code grant} covers {@code required}. */
    public static boolean matches(String grant, String required) {
        if (grant == null || required == null || grant.isBlank() || required.isBlank()) {
            return false;
        }
        if (EVERYTHING.equals(grant) || grant.equals(required)) {
            return true;
        }
        if (!grant.endsWith(SUBTREE_SUFFIX)) {
            return false;
        }
        String prefix = grant.substring(0, grant.length() - SUBTREE_SUFFIX.length());
        return !prefix.isEmpty() && required.startsWith(prefix + SEPARATOR);
    }

    /** Whether any of these grants covers {@code required} — a user's effective answer. */
    public static boolean anyMatches(Collection<String> grants, Permission required) {
        if (grants == null) {
            return false;
        }
        for (String grant : grants) {
            if (matches(grant, required.id())) {
                return true;
            }
        }
        return false;
    }
}
