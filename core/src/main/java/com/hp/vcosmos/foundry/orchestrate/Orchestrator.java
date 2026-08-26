package com.hp.vcosmos.foundry.orchestrate;

import com.hp.vcosmos.foundry.model.run.WorkflowRun;

/** One per run, chosen by strategy. Decides what tasks to seed / when the run ends. */
public interface Orchestrator {
    void reconcile(WorkflowRun run, Engine engine);
}
