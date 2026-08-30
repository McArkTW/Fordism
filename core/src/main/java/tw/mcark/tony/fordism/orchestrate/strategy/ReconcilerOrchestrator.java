package tw.mcark.tony.fordism.orchestrate.strategy;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.ChildRunRequest;
import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskSeed;
import tw.mcark.tony.fordism.model.workflow.Step;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import tw.mcark.tony.fordism.orchestrate.Engine;
import tw.mcark.tony.fordism.orchestrate.Orchestrator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Run the generator each iteration; stop on "done" or maxIterations.
 *
 * <p>An iteration may also ask for whole WORKFLOW RUNS rather than doing the work itself — it names
 * them in its result and the engine starts one child run each. The iteration is not over until they
 * have all ended, and a child that did not end DONE fails the parent: a reconciler whose delegated
 * work failed has not converged, and letting it loop on would hide that behind another pass.
 */
public final class ReconcilerOrchestrator implements Orchestrator {
    public void reconcile(WorkflowRun run, Engine engine) {
        Workflow workflow = engine.workflowFor(run);
        Step generator = workflow.generator();
        if (generator == null) {
            engine.finishRun(run, WorkflowRunState.FAILED);
            return;
        }
        Optional<Task> planted = engine.currentTask(run, run.iteration);
        if (planted.isEmpty()) {
            if (run.iteration >= workflow.maxIterations()) {
                // Spent, not stuck — nothing to rescue. See ReworkOrchestrator.
                engine.finishRun(run, WorkflowRunState.FAILED);
                return;
            }
            // Each iteration continues from the last one's workspace, so every pass after the first
            // includes the previous result whatever the generator step declared.
            String previousWorkspace = run.iteration == 0 ? null
                    : engine.currentTask(run, run.iteration - 1).map(previous -> previous.workspacePath).orElse(null);
            TaskSeed seed = TaskSeed.of(run.iteration, generator, run.parameterValues, previousWorkspace, 1);
            engine.seedTask(run, run.iteration > 0 ? seed.includingPreviousResult() : seed);
            return;
        }
        switch (planted.get().state) {
            case COLLECTED -> advance(run, engine, planted.get());
            case FAILED, REAPED -> engine.finishRun(run, WorkflowRunState.FAILED);
            default -> { /* wait. ASKED never reaches here — Engine parks the run first. */ }
        }
    }

    /**
     * What a collected iteration means: start the runs it asked for, wait for them, then read its
     * verdict.
     *
     * <p>The verdict is read LAST. An iteration that delegated its work and then said "done" is
     * claiming a result its children have not produced yet, so believing it before they finish
     * would end the parent over work still in flight.
     */
    private static void advance(WorkflowRun run, Engine engine, Task iteration) {
        Children children = Children.of(run, engine, iteration);
        if (children.notStartedYet()) {
            children.start(engine);
            return;
        }
        if (children.anyStillGoing()) {
            return;
        }
        if (children.anyEndedBadly()) {
            engine.finishRun(run, WorkflowRunState.FAILED);
            return;
        }
        if (iteration.reports("done")) {
            engine.finishRun(run, WorkflowRunState.DONE);
            return;
        }
        run.iteration++;
        engine.saveRun(run);
    }

    /**
     * The child runs of one iteration: what it asked for, and what is already running.
     *
     * <p>Scoped to the iteration, not the run. A reconciler loops, so "the children of this parent"
     * would include the ones a previous pass already finished — and a pass that read those would
     * believe its own children were done before they had started.
     */
    private record Children(WorkflowRun parent, List<ChildRunRequest> requested, List<WorkflowRun> started) {

        static Children of(WorkflowRun parent, Engine engine, Task iteration) {
            List<ChildRunRequest> requested = iteration.childRuns == null ? List.of() : iteration.childRuns;
            List<WorkflowRun> started = new ArrayList<>();
            for (WorkflowRun child : engine.runs().children(parent.id)) {
                if (child.parentIteration == parent.iteration) {
                    started.add(child);
                }
            }
            return new Children(parent, requested, started);
        }

        boolean notStartedYet() {
            return !requested.isEmpty() && started.isEmpty();
        }

        void start(Engine engine) {
            for (ChildRunRequest request : requested) {
                engine.createChildRun(parent, request);
            }
        }

        /** A child parked on a question counts as going: someone can still answer it. */
        boolean anyStillGoing() {
            return started.stream().anyMatch(child -> !child.state.isTerminal());
        }

        /** Only asked once nothing is still going, so anything other than DONE ended badly. */
        boolean anyEndedBadly() {
            return started.stream().anyMatch(child -> child.state != WorkflowRunState.DONE);
        }
    }
}
