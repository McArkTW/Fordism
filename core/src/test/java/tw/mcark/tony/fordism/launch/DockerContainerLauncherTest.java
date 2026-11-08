package tw.mcark.tony.fordism.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.credential.CredentialStore;
import tw.mcark.tony.fordism.model.task.NetworkPolicy;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import tw.mcark.tony.fordism.model.workflow.ArtifactAccess;
import tw.mcark.tony.fordism.secret.SecretVault;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The container edge — the flags that protect the HOST, and the rule that scopes a rescue secret
 * to the step it was meant for. Both are asserted on the assembled state rather than by running
 * docker: `runCommand` is the argv that would be exec'd, and `allowedSecretNames` is the filter
 * `environment()` applies to the run's vault.
 *
 * <p>Neither touches what the agent may do INSIDE its container — that is the point of the design,
 * and there is deliberately nothing here that would restrict it.
 */
class DockerContainerLauncherTest {

    @TempDir
    Path stateDir;

    private DockerContainerLauncher launcher() {
        FordismConfiguration configuration = new FordismConfiguration();
        return new DockerContainerLauncher(configuration, new ModelRegistry(configuration,
                new tw.mcark.tony.fordism.agentprofile.AgentProfileStore(stateDir)),
                new SecretVault(), new CredentialStore(stateDir));
    }

    private static Task task(NetworkPolicy network) {
        Task task = new Task("t1", "run-1", 0, "session-1");
        task.hostWorkspacePath = "/var/lib/fordism/workspaces/t1";
        task.config = new TaskConfiguration("m", 600, network, 3);
        return task;
    }

    @Test
    void the_run_command_drops_capabilities_and_forbids_privilege_escalation() {
        List<String> cmd = launcher().runCommand(task(NetworkPolicy.NONE), "fd-t1", Path.of("x.env"));

        assertTrue(adjacent(cmd, "--cap-drop", "ALL"), cmd.toString());
        assertTrue(cmd.contains("--security-opt") && cmd.contains("no-new-privileges"), cmd.toString());
        assertTrue(adjacent(cmd, "-v", "/var/lib/fordism/workspaces/t1:/workspace"), cmd.toString());
    }

    @Test
    void a_step_that_asked_for_no_network_gets_none() {
        assertTrue(adjacent(launcher().runCommand(task(NetworkPolicy.NONE), "fd-t1", Path.of("x")),
                "--network", "none"));
    }

    @Test
    void a_step_that_asked_for_full_egress_gets_the_open_internet() {
        // bridge is docker's route to the internet — the one a compromised agent could exfiltrate
        // through, which is exactly why it is opt-in and visible in the outline.
        assertTrue(adjacent(launcher().runCommand(task(NetworkPolicy.FULL), "fd-t1", Path.of("x")),
                "--network", "bridge"));
    }

    @Test
    void the_default_step_config_has_no_network() {
        // The agent talks to core through the mounted filesystem, never HTTP, so a step that says
        // nothing about egress needs none — deny by default.
        assertEquals(NetworkPolicy.NONE, TaskConfiguration.defaults().network());
        assertEquals(NetworkPolicy.NONE, NetworkPolicy.from(null));
        assertEquals(NetworkPolicy.NONE, NetworkPolicy.from("ful"), "a typo fails closed, not open");
        assertEquals(NetworkPolicy.FORDISM_ONLY, NetworkPolicy.from("fordism-only"));
        assertEquals(NetworkPolicy.FULL, NetworkPolicy.from("full"));
    }

    // ---- the run's artifact ----

    private static Task withArtifact(ArtifactAccess access) {
        Task task = task(NetworkPolicy.NONE);
        task.hostArtifactPath = "/var/lib/fordism/workspaces/_artifacts/run-1";
        task.artifact = access;
        return task;
    }

    @Test
    void a_step_that_may_write_the_artifact_gets_it_mounted_read_write() {
        List<String> cmd = launcher().runCommand(withArtifact(ArtifactAccess.WRITE), "fd-t1", Path.of("x"));

        assertTrue(adjacent(cmd, "-v", "/var/lib/fordism/workspaces/_artifacts/run-1:/workspace/artifact"),
                cmd.toString());
    }

    @Test
    void a_step_that_could_run_beside_a_sibling_gets_the_artifact_read_only() {
        // Docker enforces it, not the agent's manners: two agents never write one working tree.
        List<String> cmd = launcher().runCommand(withArtifact(ArtifactAccess.READ_ONLY), "fd-t1", Path.of("x"));

        assertTrue(adjacent(cmd, "-v", "/var/lib/fordism/workspaces/_artifacts/run-1:/workspace/artifact:ro"),
                cmd.toString());
        assertTrue(adjacent(cmd, "-v", "/var/lib/fordism/workspaces/t1:/workspace"),
                "the workspace itself stays the agent's to write: " + cmd);
    }

    @Test
    void the_artifact_is_mounted_after_the_workspace_it_sits_inside() {
        // Docker applies mounts in order; the nested one has to land on top of the workspace mount.
        List<String> cmd = launcher().runCommand(withArtifact(ArtifactAccess.WRITE), "fd-t1", Path.of("x"));

        assertTrue(cmd.indexOf("/var/lib/fordism/workspaces/t1:/workspace")
                < cmd.indexOf("/var/lib/fordism/workspaces/_artifacts/run-1:/workspace/artifact"), cmd.toString());
    }

    @Test
    void a_task_staged_before_artifacts_existed_mounts_none() {
        // Restored from an old snapshot: no artifact path, so no "null:/workspace/artifact" mount.
        List<String> cmd = launcher().runCommand(task(NetworkPolicy.NONE), "fd-t1", Path.of("x"));

        assertTrue(cmd.stream().noneMatch(argument -> argument.contains("/workspace/artifact")), cmd.toString());
    }

    // ---- rescue-secret scoping ----

    @Test
    void a_step_receives_a_rescue_secret_only_if_it_declared_or_was_answered_that_key() {
        Task declared = new Task("t", "r", 0, "s");
        declared.credentials = List.of("REGISTRY_TOKEN");
        assertEquals(Set.of("REGISTRY_TOKEN"), DockerContainerLauncher.allowedSecretNames(declared));

        Task answered = new Task("t", "r", 1, "s");
        answered.grantedSecretNames = List.of("ONE_OFF_TOKEN");
        assertEquals(Set.of("ONE_OFF_TOKEN"), DockerContainerLauncher.allowedSecretNames(answered));
    }

    @Test
    void a_step_that_neither_declared_nor_asked_receives_no_run_secret() {
        // The over-share this closes: a token typed to rescue one step used to reach every later
        // container in the run. A passive step allows nothing, so the filter drops all of it.
        Task passive = new Task("t", "r", 2, "s");
        assertTrue(DockerContainerLauncher.allowedSecretNames(passive).isEmpty());
    }

    @Test
    void the_asking_step_and_a_declaring_step_can_both_hold_the_same_key() {
        Task both = new Task("t", "r", 0, "s");
        both.credentials = List.of("A", "B");
        both.grantedSecretNames = List.of("B", "C");
        assertEquals(Set.of("A", "B", "C"), DockerContainerLauncher.allowedSecretNames(both));
    }

    /** True when {@code b} immediately follows {@code a} in the argv — a flag and its value. */
    private static boolean adjacent(List<String> argv, String a, String b) {
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (argv.get(i).equals(a) && argv.get(i + 1).equals(b)) {
                return true;
            }
        }
        return false;
    }
}
