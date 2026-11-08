package tw.mcark.tony.fordism.model.workflow;

import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import java.util.List;

/**
 * The universal step atom (template + task), plus optional per-strategy wiring.
 *
 * <p>{@code artifact} is computed by the loader from the workflow's shape, never declared — see
 * {@link ArtifactAccess}. Absent, it is {@link ArtifactAccess#WRITE}: a step on its own has nobody
 * beside it.
 *
 * <p>{@code continues} is declared: this step runs as the agent of the step it continues — same
 * workspace, same session, a new task. Which step that is follows from the strategy (see
 * {@link Workflow#continuedStep}); the loader refuses it wherever there is not exactly one.
 */
public record Step(String id, String template, String task, boolean includePreviousResult,
                   List<String> dependsOn, OnFail onFail, String forEach, String when,
                   TaskConfiguration config, ArtifactAccess artifact, boolean continues) {

    public Step {
        artifact = artifact == null ? ArtifactAccess.WRITE : artifact;
    }

    /** A step that starts its own agent — the shape every step had before {@code continues}. */
    public Step(String id, String template, String task, boolean includePreviousResult,
                List<String> dependsOn, OnFail onFail, String forEach, String when,
                TaskConfiguration config, ArtifactAccess artifact) {
        this(id, template, task, includePreviousResult, dependsOn, onFail, forEach, when, config, artifact, false);
    }

    /** The same step with the access the workflow's shape allows it. */
    public Step withArtifact(ArtifactAccess access) {
        return new Step(id, template, task, includePreviousResult, dependsOn, onFail, forEach, when, config, access,
                continues);
    }
}
