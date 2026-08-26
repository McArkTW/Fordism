package com.hp.vcosmos.foundry.field;

import com.hp.vcosmos.foundry.model.task.Task;

/** Decides the Reaper's branch on rot: resume the same session, reseed fresh, or escalate. */
public final class CullPolicy {
    public CullDecision decide(Task task) {
        if (task.attempt >= task.config.maximumAttempts()) {
            return CullDecision.ESCALATE;
        }
        return CullDecision.RESUME;
    }
}
