package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import tw.mcark.tony.fordism.agentprofile.AgentProfile;
import tw.mcark.tony.fordism.agentprofile.AgentProfileView;
import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.credential.Credential;
import tw.mcark.tony.fordism.credential.CredentialView;
import tw.mcark.tony.fordism.skill.SkillView;
import tw.mcark.tony.fordism.workspace.AgentTemplate;
import tw.mcark.tony.fordism.workspace.ResultFile;
import tw.mcark.tony.fordism.workspace.TemplateView;
import tw.mcark.tony.fordism.workspace.TokenUsage;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The API's field names, pinned.
 *
 * <p>These responses were hand-built maps until they became records. The app's TypeScript types are
 * written against these exact names, and nothing else checks them — so the expectations below are
 * the shapes read off the running UAT instance before the change. A rename that breaks a board now
 * fails here instead.
 */
class ApiShapeTest {
    private static final Gson GSON = new Gson();

    private static Set<String> fieldsOf(Object value) {
        return new TreeSet<>(GSON.toJsonTree(value).getAsJsonObject().keySet());
    }

    private static Set<String> expect(String... names) {
        return new TreeSet<>(Set.of(names));
    }

    @Test
    void a_run_in_the_list() {
        assertEquals(expect("id", "workflow", "state", "createdAt", "durationMs"),
                fieldsOf(new Views.RunSummary("r1", "qc-linear", "DONE", 1L, 2L)));
    }

    @Test
    void a_run_with_its_tasks() {
        assertEquals(expect("id", "workflow", "strategy", "state", "createdAt", "workflowSnapshot", "tasks"),
                fieldsOf(new Views.RunDetail("r1", "qc-linear", "linear", "DONE", 1L, "name: x", List.of())));
    }

    @Test
    void a_task_carries_everything_the_run_page_renders() {
        Views.TaskDetail task = new Views.TaskDetail("t1", 0, "COLLECTED", "probe-rw", "s1", "/ws",
                "did it", "pass", 1L, 1, "boom", "which one?", List.of("GITHUB_TOKEN"),
                List.of("HP_PASSWORD"), 42L, new TokenUsage(1, 2, 3, 4));
        assertEquals(expect("taskId", "step", "state", "template", "session", "workspace", "summary",
                "verdict", "createdAt", "attempt", "error", "question", "secretsRequested",
                "secretsHeld", "durationMs", "usage"), fieldsOf(task));
    }

    @Test
    void an_absent_optional_field_is_omitted_not_null() {
        // Gson drops nulls, which is what the hand-built maps did — the app checks for absence.
        Views.TaskDetail running = new Views.TaskDetail("t1", 0, "RUNNING", "probe-rw", "s1", "/ws",
                null, null, 1L, 1, null, null, List.of(), List.of(), 0L, null);
        Set<String> fields = fieldsOf(running);
        assertFalse(fields.contains("summary"), fields.toString());
        assertFalse(fields.contains("verdict"), fields.toString());
        assertFalse(fields.contains("error"), fields.toString());
        assertFalse(fields.contains("usage"), fields.toString());
    }

    @Test
    void token_usage_keeps_the_provider_spelling() {
        JsonObject usage = GSON.toJsonTree(new TokenUsage(10, 20, 30, 2)).getAsJsonObject();
        assertEquals(expect("input_tokens", "output_tokens", "total", "turns"),
                new TreeSet<>(usage.keySet()));
        assertEquals(10, usage.get("input_tokens").getAsLong());
    }

    @Test
    void a_question_in_the_inbox() {
        assertEquals(expect("taskId", "runId", "workflow", "step", "template", "question",
                        "secretsRequested", "secretsHeld", "summary", "createdAt"),
                fieldsOf(new Views.Question("t1", "r1", "wi-quick", 0, "rescue-claude", "which?",
                        List.of(), List.of(), "stopped", 1L)));
    }

    @Test
    void a_result_bundle_and_one_of_its_files() {
        assertEquals(expect("taskId", "workspace", "files"),
                fieldsOf(new Views.TaskResultFiles("t1", "/ws", List.of())));
        assertEquals(expect("name", "size", "binary", "content"),
                fieldsOf(new ResultFile("answer.md", 12L, false, "hello")));
    }

    @Test
    void a_workflow_in_the_list_and_in_full() {
        assertEquals(expect("name", "strategy", "steps", "description", "tags", "templates"),
                fieldsOf(new Views.WorkflowSummary("qc-linear", "linear", 2, "", List.of(), List.of())));
        assertEquals(expect("name", "description", "strategy", "tags", "maxIterations", "parameters",
                        "steps", "generator", "yaml"),
                fieldsOf(new Views.WorkflowDetail("qc-linear", "", "linear", List.of(), 10,
                        List.of(), List.of(), "", "name: qc-linear")));
    }

    @Test
    void a_workflow_without_yaml_omits_it_so_validate_and_get_can_share_a_shape() {
        assertFalse(fieldsOf(new Views.WorkflowDetail("x", "", "linear", List.of(), 10,
                List.of(), List.of(), "", null)).contains("yaml"));
    }

    @Test
    void a_step_and_a_parameter_as_the_editor_reads_them() {
        assertEquals(expect("id", "template", "dependsOn", "forEach", "when",
                        "includePreviousResult", "timeoutSeconds"),
                fieldsOf(new Views.WorkflowStep("s0", "generic", List.of(), "", "", false, 600)));
        assertEquals(expect("name", "label", "type", "required", "defaultValue", "help"),
                fieldsOf(new Views.WorkflowParameter("topic", "Topic", "text", false, "", "")));
    }

    @Test
    void a_template_row_carries_identity_only_and_the_detail_carries_the_rest() {
        assertEquals(expect("id", "name"),
                fieldsOf(TemplateView.summary(new AgentTemplate("t1", "worker", "claude", "m",
                        List.of(), List.of(), ""))));
        assertEquals(expect("id", "name", "exists", "agentProfile", "model", "skills",
                        "credentials", "instructions"),
                fieldsOf(new TemplateView("t1", "worker", true, "claude", "m",
                        List.of("access/github"), List.of("GITHUB_TOKEN"), "be brief")));
    }

    @Test
    void a_skill_row_and_an_opened_skill() {
        assertEquals(expect("name", "description", "enabled"),
                fieldsOf(SkillView.summary("access/github", "gh access", true)));
        assertEquals(expect("name", "description", "enabled", "exists", "content", "files"),
                fieldsOf(new SkillView("access/github", "gh access", true, true, "# gh",
                        List.of("SKILL.md"))));
    }

    @Test
    void an_agent_profile_never_carries_the_key_itself() {
        AgentProfileView view = AgentProfileView.of(
                new AgentProfile("p1", "claude-prod", "https://api.anthropic.com", "sk-secret",
                        "claude-opus", AgentTool.CLAUDE_CODE));
        assertEquals(expect("id", "name", "baseUrl", "model", "tool", "hasKey"), fieldsOf(view));
        assertFalse(GSON.toJson(view).contains("sk-secret"));
        assertEquals(expect("id", "name", "baseUrl", "model", "tool", "hasKey", "exists"),
                fieldsOf(view.found()));
    }

    @Test
    void a_credential_never_carries_its_value() {
        CredentialView view = CredentialView.of(
                new Credential("GITHUB_TOKEN", "ghp-secret", "for pushes", 7L));
        assertFalse(GSON.toJson(view).contains("ghp-secret"));
        assertEquals(expect("key", "note", "hasValue", "updatedAt"), fieldsOf(view));
        assertEquals(expect("key", "note", "hasValue", "updatedAt", "usedBy"),
                fieldsOf(view.usedBy(List.of("worker"))));
    }

    @Test
    void the_login_screen_reads_the_providers_and_whether_the_install_is_unclaimed() {
        assertEquals(expect("providers", "bootstrapRequired"),
                fieldsOf(new Views.AuthProviders(List.of(new Views.Provider("local")), true)));
        assertEquals(expect("id"), fieldsOf(new Views.Provider("local")));
    }

    @Test
    void the_signed_in_user_carries_the_grants_the_app_hides_actions_by() {
        assertEquals(expect("id", "email", "displayName", "groups", "permissions"),
                fieldsOf(new Views.Me("u1", "dana@example.com", "Dana", List.of("admins"), List.of("*"))));
    }

    @Test
    void a_user_row_lists_the_ways_in_and_never_the_secret_behind_one() {
        Views.UserSummary user = new Views.UserSummary("u1", "dana@example.com", "Dana",
                List.of(new Views.Identity("local", "dana@example.com")));
        assertEquals(expect("id", "email", "displayName", "identities"), fieldsOf(user));
        assertEquals(expect("provider", "subject"), fieldsOf(new Views.Identity("google", "sub-1")));
        // There is no field a hash could travel in — that is the point of the record.
        assertFalse(GSON.toJson(user).contains("pbkdf2"));
        assertFalse(GSON.toJson(user).contains("password"));
    }

    @Test
    void a_group_is_its_members_and_its_grant_patterns() {
        assertEquals(expect("id", "name", "members", "grants"),
                fieldsOf(new Views.GroupSummary("g1", "admins", List.of("u1"), List.of("*"))));
    }

    @Test
    void preflight_and_a_started_run() {
        assertEquals(expect("ready", "problem"), fieldsOf(new Views.Preflight(true, "")));
        assertEquals(expect("runId", "workflow", "state"),
                fieldsOf(new Views.RunStarted("r1", "qc-linear", "ACTIVE")));
    }
}
