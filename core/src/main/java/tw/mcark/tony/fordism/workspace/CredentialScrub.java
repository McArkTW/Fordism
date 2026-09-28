package tw.mcark.tony.fordism.workspace;

import tw.mcark.tony.fordism.model.task.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.tinylog.Logger;

/**
 * Deletes the credential files an agent tool had to write inside its workspace.
 *
 * <p>Nearly every tool takes its API key from the environment, which dies with the container and
 * never touches the disk — and the tools that need a config file are pointed at the variable
 * ({@code env_key} for codex, {@code {env:OPENAI_API_KEY\}} for opencode) rather than handed the
 * value. A tool that can only read a key from a file on disk is the exception this exists for: the
 * workspace is bind-mounted from the host, kept after the run and downloadable, so a key written
 * there outlives the container that used it by as long as the workspace survives.
 *
 * <p>The list is configuration ({@code FORDISM_CREDENTIAL_FILES}), not a tool name in a switch:
 * core needs to know which paths hold a secret, never which tool wrote them. That is also what
 * makes this useful before the tool that needs it exists — adding such a tool is a config line,
 * not a change here.
 *
 * <p>This runs host-side, after the container is gone, so it covers the exit the agent's own shell
 * could never have trapped: {@link tw.mcark.tony.fordism.field.Reaper} SIGKILLs a timed-out or
 * stale container. It runs on every path that ends a task, including one that only paused to ask
 * and including a retry — the next attempt starts a fresh container that writes the file again.
 */
public final class CredentialScrub {

    private final List<String> credentialFiles;

    public CredentialScrub(List<String> credentialFiles) {
        this.credentialFiles = List.copyOf(credentialFiles);
    }

    /**
     * Removes every configured credential file from the task's workspace.
     *
     * <p>Never throws. A task that has already ended must not be held up because its workspace is
     * gone, read-only, or was never staged — but a failure to delete a live key is worth a line in
     * the log, because it means the key is still on disk.
     */
    public void scrub(Task task) {
        if (task == null || task.workspacePath == null || task.workspacePath.isBlank()) {
            return;
        }
        Path workspace = Paths.get(task.workspacePath).normalize();
        for (String relative : credentialFiles) {
            Path file = workspace.resolve(relative).normalize();
            // The list is operator-supplied, so "../../etc/something" is a configuration mistake
            // this refuses rather than acts on. Scrubbing is deletion; it stays inside the one
            // directory this task owns.
            if (!file.startsWith(workspace)) {
                Logger.warn("scrub task {} refused {} — outside the workspace", task.id, relative);
                continue;
            }
            try {
                if (Files.deleteIfExists(file)) {
                    Logger.info("scrub task {} removed {}", task.id, relative);
                }
            } catch (Exception e) {
                Logger.warn("scrub task {} could not remove {} — a credential may remain on disk: {}",
                        task.id, relative, e.toString());
            }
        }
    }
}
