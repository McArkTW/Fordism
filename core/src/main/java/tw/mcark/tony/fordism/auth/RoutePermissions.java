package tw.mcark.tony.fordism.auth;

import io.javalin.http.HandlerType;
import java.util.List;
import java.util.Optional;

/**
 * The permission each API route declares, in one table.
 *
 * <p>Declared here rather than beside each handler so the whole authorisation surface is one
 * readable page — the question "what can a viewer reach" is answered by reading this file, not by
 * grepping annotations across nine controllers.
 *
 * <p>A route with no entry is denied, not allowed. A new endpoint that nobody remembered to
 * classify fails closed for everyone including admins, which is a bug report on the first click
 * instead of a quiet hole.
 */
public final class RoutePermissions {

    /** Ordered: the first rule that covers a request wins, so specific paths precede subtrees. */
    private static final List<Rule> RULES = List.of(
            // ---- workflows ----
            new Rule(HandlerType.GET, "/api/workflows", Permission.WORKFLOW_READ),
            new Rule(HandlerType.GET, "/api/workflows/**", Permission.WORKFLOW_READ),
            new Rule(HandlerType.POST, "/api/workflows/*/run", Permission.WORKFLOW_RUN),
            new Rule(HandlerType.POST, "/api/workflows", Permission.WORKFLOW_WRITE),
            new Rule(HandlerType.POST, "/api/workflows/**", Permission.WORKFLOW_WRITE),
            new Rule(HandlerType.PUT, "/api/workflows/**", Permission.WORKFLOW_WRITE),
            new Rule(HandlerType.DELETE, "/api/workflows/**", Permission.WORKFLOW_WRITE),

            // ---- agent templates ----
            new Rule(HandlerType.GET, "/api/templates", Permission.TEMPLATE_READ),
            new Rule(HandlerType.GET, "/api/templates/**", Permission.TEMPLATE_READ),
            new Rule(HandlerType.POST, "/api/templates", Permission.TEMPLATE_WRITE),
            new Rule(HandlerType.POST, "/api/templates/**", Permission.TEMPLATE_WRITE),
            new Rule(HandlerType.PUT, "/api/templates/**", Permission.TEMPLATE_WRITE),
            new Rule(HandlerType.DELETE, "/api/templates/**", Permission.TEMPLATE_WRITE),

            // ---- skills ----
            new Rule(HandlerType.GET, "/api/skills", Permission.SKILL_READ),
            new Rule(HandlerType.GET, "/api/skills-source", Permission.SKILL_READ),
            new Rule(HandlerType.GET, "/api/skills/**", Permission.SKILL_READ),
            new Rule(HandlerType.POST, "/api/skills", Permission.SKILL_WRITE),
            new Rule(HandlerType.POST, "/api/skills-state", Permission.SKILL_WRITE),
            new Rule(HandlerType.POST, "/api/skills/**", Permission.SKILL_WRITE),
            new Rule(HandlerType.DELETE, "/api/skills/**", Permission.SKILL_WRITE),

            // ---- agent profiles ----
            new Rule(HandlerType.GET, "/api/agent-profiles", Permission.PROFILE_READ),
            new Rule(HandlerType.GET, "/api/agent-profiles/**", Permission.PROFILE_READ),
            new Rule(HandlerType.POST, "/api/agent-profiles", Permission.PROFILE_WRITE),
            new Rule(HandlerType.POST, "/api/agent-profiles/**", Permission.PROFILE_WRITE),
            new Rule(HandlerType.PUT, "/api/agent-profiles/**", Permission.PROFILE_WRITE),
            new Rule(HandlerType.DELETE, "/api/agent-profiles/**", Permission.PROFILE_WRITE),

            // ---- credentials (values never leave the server; these gate the metadata) ----
            new Rule(HandlerType.GET, "/api/credentials", Permission.CREDENTIAL_READ),
            new Rule(HandlerType.GET, "/api/credentials/**", Permission.CREDENTIAL_READ),
            new Rule(HandlerType.PUT, "/api/credentials/**", Permission.CREDENTIAL_WRITE),
            new Rule(HandlerType.DELETE, "/api/credentials/**", Permission.CREDENTIAL_WRITE),

            // ---- runs, tasks and the questions inbox ----
            new Rule(HandlerType.GET, "/api/runs", Permission.RUN_READ),
            new Rule(HandlerType.GET, "/api/runs/*", Permission.RUN_READ),
            new Rule(HandlerType.GET, "/api/questions", Permission.RUN_READ),
            new Rule(HandlerType.GET, "/api/tasks/*/result", Permission.RUN_READ),
            new Rule(HandlerType.GET, "/api/tasks/*/transcript", Permission.RUN_READ),
            // A workspace zip carries whatever the agent wrote, secrets included — its own permission.
            new Rule(HandlerType.GET, "/api/tasks/*/result.zip", Permission.RUN_WORKSPACE_DOWNLOAD),
            new Rule(HandlerType.GET, "/api/tasks/*/workspace.zip", Permission.RUN_WORKSPACE_DOWNLOAD),
            new Rule(HandlerType.POST, "/api/tasks/*/answer", Permission.RUN_ANSWER),
            new Rule(HandlerType.POST, "/api/runs/*/abandon", Permission.RUN_CONTROL),

            // ---- accounts ----
            new Rule(HandlerType.GET, "/api/users", Permission.USER_READ),
            new Rule(HandlerType.POST, "/api/users", Permission.USER_WRITE),
            new Rule(HandlerType.POST, "/api/users/**", Permission.USER_WRITE),
            new Rule(HandlerType.PUT, "/api/users/**", Permission.USER_WRITE),
            new Rule(HandlerType.DELETE, "/api/users/**", Permission.USER_WRITE),
            new Rule(HandlerType.GET, "/api/groups", Permission.GROUP_READ),
            new Rule(HandlerType.POST, "/api/groups", Permission.GROUP_WRITE),
            new Rule(HandlerType.POST, "/api/groups/**", Permission.GROUP_WRITE),
            new Rule(HandlerType.PUT, "/api/groups/**", Permission.GROUP_WRITE),
            new Rule(HandlerType.DELETE, "/api/groups/**", Permission.GROUP_WRITE));

    private RoutePermissions() {}

    /** What this request must be allowed to do; empty when no rule claims it, which means deny. */
    public static Optional<Permission> required(HandlerType method, String path) {
        String normalized = trimTrailingSlash(path);
        for (Rule rule : RULES) {
            if (rule.covers(method, normalized)) {
                return Optional.of(rule.permission());
            }
        }
        return Optional.empty();
    }

    /**
     * One row of the table. {@code *} stands for exactly one path segment, {@code **} for one or
     * more — Javalin's {@code /api/skills/<name>} accepts a namespaced name with slashes in it.
     */
    private record Rule(HandlerType method, String pattern, Permission permission) {

        boolean covers(HandlerType requestMethod, String path) {
            return method == requestMethod && segmentsMatch(pattern.split("/"), path.split("/"));
        }
    }

    private static boolean segmentsMatch(String[] pattern, String[] path) {
        for (int index = 0; index < pattern.length; index++) {
            if ("**".equals(pattern[index])) {
                return path.length > index;
            }
            if (index >= path.length) {
                return false;
            }
            if (!"*".equals(pattern[index]) && !pattern[index].equals(path[index])) {
                return false;
            }
        }
        return pattern.length == path.length;
    }

    private static String trimTrailingSlash(String path) {
        String value = path == null ? "" : path;
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
