package tw.mcark.tony.fordism.auth;

/**
 * The concrete leaf permission a route declares.
 *
 * <p>A grant is a pattern a human typed into a group; this is the other side of the match, and it
 * is an enum so a route can never ask for a permission nobody can spell. The dotted {@link #id()}
 * is what {@link PermissionMatcher} compares and what a grant pattern names.
 */
public enum Permission {
    WORKFLOW_READ("workflow.read"),
    WORKFLOW_WRITE("workflow.write"),
    WORKFLOW_RUN("workflow.run"),
    TEMPLATE_READ("template.read"),
    TEMPLATE_WRITE("template.write"),
    SKILL_READ("skill.read"),
    SKILL_WRITE("skill.write"),
    PROFILE_READ("profile.read"),
    PROFILE_WRITE("profile.write"),
    CREDENTIAL_READ("credential.read"),
    CREDENTIAL_WRITE("credential.write"),
    RUN_READ("run.read"),
    RUN_WORKSPACE_DOWNLOAD("run.workspace.download"),
    RUN_ANSWER("run.answer"),
    RUN_CONTROL("run.control"),
    USER_READ("user.read"),
    USER_WRITE("user.write"),
    GROUP_READ("group.read"),
    GROUP_WRITE("group.write");

    private final String id;

    Permission(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
