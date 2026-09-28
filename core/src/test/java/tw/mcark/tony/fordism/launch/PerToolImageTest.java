package tw.mcark.tony.fordism.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.agentprofile.AgentProfile;
import tw.mcark.tony.fordism.agentprofile.AgentProfileStore;
import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.agentprofile.ApiFormat;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.credential.CredentialStore;
import tw.mcark.tony.fordism.model.task.NetworkPolicy;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import tw.mcark.tony.fordism.secret.SecretVault;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task runs the image belonging to its tool, not one image holding every CLI. Getting this wrong
 * is not a slow build, it is a container whose CLI is absent — {@code AGENT_TYPE=codex} in an image
 * with no {@code codex} on its PATH produces no result at all, which the engine reads as rotten.
 */
class PerToolImageTest {

    @TempDir
    Path profilesDir;

    private static Task task(String profileName) {
        Task task = new Task("t1", "run-1", 0, "session-1");
        task.hostWorkspacePath = "/var/lib/fordism/workspaces/t1";
        task.config = new TaskConfiguration("m", 600, NetworkPolicy.NONE, 3);
        task.agentProfile = profileName;
        return task;
    }

    private String imageFor(AgentTool tool, ApiFormat format) throws IOException {
        AgentProfileStore profiles = new AgentProfileStore(profilesDir);
        profiles.create(new AgentProfile(null, "p-" + tool.wireName(), "https://example.test",
                "sk-x", "some-model", tool, format));
        FordismConfiguration configuration = new FordismConfiguration();
        DockerContainerLauncher launcher = new DockerContainerLauncher(configuration,
                new ModelRegistry(configuration, profiles), new SecretVault(), new CredentialStore(profilesDir));
        List<String> cmd = launcher.runCommand(task("p-" + tool.wireName()), "fd-t1", Path.of("x.env"));
        // The image is the last argument: everything after it would be the container's own command.
        return cmd.get(cmd.size() - 1);
    }

    @Test
    void each_tool_runs_its_own_image() throws IOException {
        assertEquals("fordism/fordism-agent-codex:local", imageFor(AgentTool.CODEX, ApiFormat.OPENAI_RESPONSES));
    }

    @Test
    void the_image_name_carries_the_tools_wire_name() {
        FordismConfiguration configuration = new FordismConfiguration();
        for (AgentTool tool : AgentTool.values()) {
            String image = configuration.agentImage(tool.wireName());
            assertTrue(image.endsWith("-" + tool.wireName() + ":local"), image);
            // Every wire name must survive as a docker image path segment; an underscore or an
            // upper-case letter here would be a tag docker refuses.
            assertTrue(tool.wireName().matches("[a-z0-9-]+"), tool.wireName());
        }
    }

    /**
     * With no profile at all the launcher still has to name an image. It falls back to claude-code,
     * the same default AgentTool.from applies — not to a prefix with nothing appended, which would
     * be an image name no build ever produces.
     */
    @Test
    void a_task_with_no_profile_falls_back_to_the_claude_code_image() {
        FordismConfiguration configuration = new FordismConfiguration();
        DockerContainerLauncher launcher = new DockerContainerLauncher(configuration,
                new ModelRegistry(configuration, new AgentProfileStore(profilesDir)),
                new SecretVault(), new CredentialStore(profilesDir));
        List<String> cmd = launcher.runCommand(task(null), "fd-t1", Path.of("x.env"));
        assertEquals("fordism/fordism-agent-claude-code:local", cmd.get(cmd.size() - 1));
    }
}
