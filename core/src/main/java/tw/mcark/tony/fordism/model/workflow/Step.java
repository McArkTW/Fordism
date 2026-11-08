package tw.mcark.tony.fordism.model.workflow;

import tw.mcark.tony.fordism.model.task.TaskConfiguration;
import java.util.List;

/**
 * The universal step atom (template + task), plus optional per-strategy wiring.
 *
 * <p>{@code artifact} is computed by the loader from the workflow's shape, never declared — see
 * {@link ArtifactAccess}. Absent, it is {@link ArtifactAccess#WRITE}: a step on its own has nobody
 * beside it.
 */
public record Step(String id, String template, String task, boolean includePreviousResult,
                   List<String> dependsOn, OnFail onFail, String forEach, String when,
                   TaskConfiguration config, ArtifactAccess artifact) {

    public Step {
        artifact = artifact == null ? ArtifactAccess.WRITE : artifact;
    }

    /** The same step with the access the workflow's shape allows it. */
    public Step withArtifact(ArtifactAccess access) {
        return new Step(id, template, task, includePreviousResult, dependsOn, onFail, forEach, when, config, access);
    }
}
