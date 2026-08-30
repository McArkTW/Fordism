package tw.mcark.tony.fordism.field;

import tw.mcark.tony.fordism.model.task.Task;

/** Decides the Reaper's branch on rot: reseed a fresh attempt while attempts remain, else escalate. */
public final class CullPolicy {
    public CullDecision decide(Task task) {
        if (task.attempt >= task.config.maximumAttempts()) {
            return CullDecision.ESCALATE;
        }
        return CullDecision.RESEED;
    }
}
