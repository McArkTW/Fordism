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

    /** Called from {@code Fordism.main}. Idempotent. */
    public static void into(GroupStore groups) {
        groups.seed(Group.named(ADMINS, List.of(PermissionMatcher.EVERYTHING)));
        groups.seed(Group.named("maintainers", List.of("workflow.*", "run.*", "template.*", "skill.*",
                "profile.*", "credential.*")));
        // Operators run the machine but do not redefine it: they start workflows and answer
        // questions, and read everything a run depends on without being able to edit it.
        groups.seed(Group.named("operators", List.of("workflow.read", "workflow.run", "run.*",
                "template.read", "skill.read", "profile.read", "credential.read")));
        groups.seed(Group.named("viewers", List.of("workflow.read", "run.read", "template.read",
                "skill.read", "profile.read", "credential.read")));
    }
}
