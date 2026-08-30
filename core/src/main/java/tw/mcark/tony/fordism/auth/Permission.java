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
    /** Deleting one, separately from editing it: an edit can be undone from the editor, this cannot. */
    WORKFLOW_DELETE("workflow.write.delete"),
    WORKFLOW_RUN("workflow.run"),
    TEMPLATE_READ("template.read"),
    TEMPLATE_WRITE("template.write"),
    SKILL_READ("skill.read"),
    SKILL_WRITE("skill.write"),
    /**
     * Installing, syncing or removing a skills repo by URL.
     *
     * <p>Its own permission because it is a different power from writing a skill: a plugin fetches
     * an archive from the internet and stages whatever is inside it into the library every agent
     * reads. Someone who may edit a SKILL.md is not thereby someone who may point this instance at
     * a URL.
     *
     * <p>The WRITE side only. Reading which plugins are installed is reading the skill library, so
     * it stays {@link #SKILL_READ} — splitting that too would take the Plugins page away from the
     * viewers who can already see every skill on it.
     *
     * <p>A descendant of {@code skill}, so a group holding {@code skill.*} keeps it — which is how
     * the seeded groups are written, and why this narrowing costs those installs nothing.
     */
    SKILL_PLUGIN_WRITE("skill.plugin.write"),
    PROFILE_READ("profile.read"),
    PROFILE_WRITE("profile.write"),
    CREDENTIAL_READ("credential.read"),
    CREDENTIAL_WRITE("credential.write"),
    RUN_READ("run.read"),
    RUN_WORKSPACE_DOWNLOAD("run.workspace.download"),
    RUN_ANSWER("run.answer"),
    RUN_ABANDON("run.control.abandon"),
    USER_READ("user.read"),
    USER_WRITE("user.write"),
    GROUP_READ("group.read"),
    GROUP_WRITE("group.write"),
    /**
     * Managing your OWN API tokens. Held by every seeded group, including viewers, because a token
     * can never do more than the account that minted it — see {@link ApiToken}.
     */
    TOKEN_READ("token.read"),
    TOKEN_WRITE("token.write"),
    /** Reading the audit trail — who did what. Admins only; it names every actor on the instance. */
    AUDIT_READ("audit.read");

    private final String id;

    Permission(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
