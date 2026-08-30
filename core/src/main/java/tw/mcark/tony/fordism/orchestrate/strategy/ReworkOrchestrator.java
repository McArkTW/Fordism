package tw.mcark.tony.fordism.orchestrate.strategy;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskSeed;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.model.workflow.OnFail;
import tw.mcark.tony.fordism.model.workflow.ReworkMode;
import tw.mcark.tony.fordism.model.workflow.Step;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.orchestrate.Orchestrator;
import java.util.Optional;

/** Linear, plus: a gate step that fails sends the work back up to maxAttempts, else FAILED. */
public final class ReworkOrchestrator implements Orchestrator {
    public void reconcile(WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        if (run.currentStepIndex >= workflow.steps().size()) {
            engine.finishRun(run, WorkflowRunState.DONE);
            return;
        }
        Step step = workflow.steps().get(run.currentStepIndex);
        Optional<Task> planted = engine.currentTask(run, run.currentStepIndex);
        Optional<Task> previous = run.currentStepIndex == 0
                ? Optional.empty()
                : engine.currentTask(run, run.currentStepIndex - 1);
        if (planted.isPresent() && isStale(planted.get(), previous.orElse(null))) {
            // Re-run it against the reworked input instead of reading last round's verdict. The
            // attempt count carries over, so it reflects how many times this step has been judged.
            engine.seedStep(run, step, planted.get().attempt + 1);
            return;
        }
        if (planted.isEmpty()) {
            engine.seedStep(run, step, 1);
            return;
        }
        Task task = planted.get();
        if (task.state == TaskState.COLLECTED) {
            OnFail onFail = step.onFail();
            if (onFail != null && task.reports("fail")) {
                escalateOrRework(Rework.from(run, engine, task), engine);
            } else {
                run.currentStepIndex++;
                engine.saveRun(run);
            }
        } else if (task.state == TaskState.FAILED || task.state == TaskState.REAPED) {
            engine.finishRun(run, WorkflowRunState.FAILED);
        }
    }

    /**
     * Send the work back, or fail the run when the gate has rejected it as often as it may.
     *
     * <p>FAILED rather than ASKED when the attempts are spent: nothing asked anything and there is
     * no task to re-arm, so ASKED would advertise an action nobody can take and the run would sit
     * in an inbox that cannot help it. Seen as atc-job-debug run 1c532485.
     */
    private static void escalateOrRework(Rework rework, Engine engine) {
        if (rework.isSpent()) {
            engine.finishRun(rework.run(), WorkflowRunState.FAILED);
            return;
        }
        rework.apply(engine);
    }

    /**
     * One decided rework: which step the work goes back to, what is already sitting there, what
     * number attempt this makes, why the gate rejected it, and whether to start that step over or
     * resume it.
     *
     * <p>A record rather than six arguments threaded through a private method — and it owns the
     * behaviour over that state, so the orchestrator's own branch stays three lines.
     */
    private record Rework(WorkflowRun run, int retryIndex, Step retryStep, Task retryTask,
                          int nextAttempt, OnFail onFail, String correction) {

        /** Read the gate's verdict against the workflow and the task already at the retry step. */
        static Rework from(WorkflowRun run, Engine engine, Task gate) {
            Workflow workflow = engine.workflowFor(run);
            OnFail onFail = workflow.steps().get(gate.stepIndex).onFail();
            int retryIndex = Engine.indexOfStep(workflow, onFail.retryStepId());
            Optional<Task> planted = engine.currentTask(run, retryIndex);
            return new Rework(run, retryIndex, workflow.steps().get(retryIndex), planted.orElse(null),
                    planted.map(task -> task.attempt).orElse(1) + 1, onFail, correctionFrom(gate));
        }

        /**
         * What a resumed agent is told. The gate's own words, because they are the correction — an
         * agent that only learns it was rejected has to guess at what for, and guessing is what the
         * gate rejected.
         */
        private static String correctionFrom(Task gate) {
            String summary = gate.summary == null ? "" : gate.summary.trim();
            if (summary.isEmpty()) {
                return "The review step rejected your last result. Read its output in the workspace,"
                        + " correct the work, and finish the task.";
            }
            return "The review step rejected your last result:\n\n" + summary
                    + "\n\nCorrect the work and finish the task.";
        }

        boolean isSpent() {
            return nextAttempt > onFail.maximumAttempts();
        }

        /**
         * {@code retry} plants a new task; {@code resume} re-arms the one already there.
         *
         * <p>Resume falls back to a fresh seed when the retry step has no host workspace to resume
         * into — a task that never reached dispatch has no session to continue, and the fallback is
         * what {@code onFail} did before this mode existed rather than a run that fails on a
         * technicality.
         *
         * <p>Either way the input is the RETRY STEP'S own last workspace, not the gate's: it is
         * continuing its own work, so {@code seedStep}'s "the step before me" is the wrong one here.
         */
        void apply(Engine engine) {
            boolean resumable = retryTask != null && retryTask.hostWorkspacePath != null;
            if (onFail.mode() == ReworkMode.RESUME && resumable) {
                engine.resumeTask(retryTask, nextAttempt, correction);
            } else {
                engine.seedTask(run, TaskSeed.of(retryIndex, retryStep, run.parameterValues,
                        retryTask == null ? null : retryTask.workspacePath, nextAttempt));
            }
            run.currentStepIndex = retryIndex;
            engine.saveRun(run);
        }
    }

    /**
     * True when this task judged an input that has since been reworked.
     *
     * <p>A rework rewinds the run to an earlier step. Every step after it already has a task, and
     * the orchestrator only seeds where there is none — so on the way back the gate's OLD verdict is
     * read again, "fail" is found again, and another rework fires without the retry ever being
     * looked at. The loop is not a loop: the retry runs, its work is never judged, and the run
     * burns its attempts and escalates no matter how good the rework was.
     *
     * <p>A task is stale when the step before it was ARMED later than the one it read. In ordinary
     * forward flow that cannot happen — a step is always armed after its predecessor.
     *
     * <p>Armed, not created: in {@code resume} mode the rework is the same task continuing, so its
     * creation time never moves and a comparison of those would never see the rework at all.
     */
    private boolean isStale(Task task, Task previous) {
        return previous != null && previous.armedAt() > task.armedAt();
    }
}
