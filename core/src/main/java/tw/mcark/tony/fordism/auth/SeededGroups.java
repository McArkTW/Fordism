package tw.mcark.tony.fordism.auth;

import java.util.List;

/**
 * The four groups a fresh install starts with, so the first admin has something to put people in
 * rather than a blank page and a permission vocabulary to guess at.
 *
 * <p>Create-if-missing, every boot, from the composition root: an operator who narrowed
 * {@code operators} must not find it widened again by the next restart, and an operator who deleted
 * one they did not want must not find it back. Only a group that is genuinely absent is created.
 */
public final class SeededGroups {

    /** The group the one-time bootstrap puts the first admin in. */
    public static final String ADMINS = "admins";

    private SeededGroups() {}

    /**
     * Called from {@code Fordism.main}. Idempotent.
     *
     * <p>Every group holds {@code token.*}: an API token is never more powerful than the account
     * that minted it, so managing your own is not a privilege to hand out separately — and a
     * viewers-only account with no way to script its reads would be a strange kind of read access.
     *
     * <p>The subtree grants are deliberate, not shorthand. v1.1 split two coarse permissions into
     * finer ones ({@code skill.plugin.*}, {@code workflow.write.delete}, {@code run.control.abandon}),
     * and a group written as {@code skill.*} keeps exactly what it had through that split while a
     * group written as {@code skill.write} narrows — which is the point of the split.
     */
    public static void into(GroupStore groups) {
        groups.seed(Group.named(ADMINS, List.of(PermissionMatcher.EVERYTHING)));
        groups.seed(Group.named("maintainers", List.of("workflow.*", "run.*", "template.*", "skill.*",
                "profile.*", "credential.*", "token.*")));
        // Operators run the machine but do not redefine it: they start workflows and answer
        // questions, and read everything a run depends on without being able to edit it.
        groups.seed(Group.named("operators", List.of("workflow.read", "workflow.run", "run.*",
                "template.read", "skill.read", "profile.read", "credential.read", "token.*")));
        groups.seed(Group.named("viewers", List.of("workflow.read", "run.read", "template.read",
                "skill.read", "profile.read", "credential.read", "token.*")));
    }
}
