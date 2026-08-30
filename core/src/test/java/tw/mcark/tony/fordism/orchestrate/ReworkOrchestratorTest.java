package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import tw.mcark.tony.fordism.model.task.TaskMode;
import tw.mcark.tony.fordism.model.task.TaskState;
import org.junit.jupiter.api.Test;

/**
 * THE CONTRACT: a failing gate sends the work back, and the gate then judges the NEW work.
 *
 * <p>Both halves are load-bearing and both were bugs. A rework rewinds the run to an earlier step,
 * and every step after it already has a task — so on the way forward the gate's OLD verdict was
 * read again, "fail" found again, and another rework fired without the retry's output ever being
 * looked at. The retry ran, its work was never judged, and the run burned every attempt however
 * good the rework was (seen on atc-job-debug run 1c532485).
 *
 * <p>The other half is what happens when the attempts are spent: FAILED, not ASKED. Nothing asked
 * anything and there is no task to re-arm, so parking it would advertise an action nobody can take.
 */
class ReworkOrchestratorTest {

    private static final String WORK_THEN_GATE = """
            name: rework-under-test
            strategy: rework
            steps:
              - id: work
                template: generic
                task: do the work
              - id: gate
                template: generic
                task: judge the work
                includePreviousResult: true
                onFail:
                  retry: work
                  maxAttempts: 2
            """;

    private final OrchestratorHarness harness = new OrchestratorHarness();

    /** Plant the work step, collect it, and plant the gate over it. */
    private WorkflowRun runUpToTheGate() {
        WorkflowRun run = harness.start(WORK_THEN_GATE);
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.reconcile(run);
        harness.reconcile(run);
        return run;
    }

    @Test
    void a_passing_gate_finishes_the_run() {
        WorkflowRun run = runUpToTheGate();

        harness.collect(run, 1, "pass");
        harness.reconcileUntilSettled(run);

        assertEquals(WorkflowRunState.DONE, run.state);
        assertEquals(2, harness.plantedCount(run), "a passing gate reworks nothing");
    }

    @Test
    void a_failing_gate_re_seeds_the_work_step_as_a_further_attempt() {
        WorkflowRun run = runUpToTheGate();
        String firstAttempt = harness.at(run, 0).id;

        harness.collect(run, 1, "fail");
        harness.reconcile(run);

        assertNotEquals(firstAttempt, harness.at(run, 0).id, "the work step is planted again");
        assertEquals(2, harness.at(run, 0).attempt, "the attempt count carries over");
        assertEquals(0, run.currentStepIndex, "the run rewinds to the retry step");
    }

    @Test
    void the_rework_continues_from_its_own_last_workspace_not_the_gates() {
        WorkflowRun run = runUpToTheGate();
        String workWorkspace = harness.at(run, 0).workspacePath;
        harness.collect(run, 1, "fail");
        String gateWorkspace = harness.at(run, 1).workspacePath;

        harness.reconcile(run);

        assertEquals(workWorkspace, harness.at(run, 0).previousWorkspace,
                "a rework is continuing its own work, so it re-reads its own last workspace");
        assertNotEquals(gateWorkspace, harness.at(run, 0).previousWorkspace);
    }

    @Test
    void the_gate_is_re_run_against_the_reworked_output_rather_than_reading_its_old_verdict() {
        WorkflowRun run = runUpToTheGate();
        String firstGate = harness.at(run, 1).id;
        harness.collect(run, 1, "fail");
        harness.reconcile(run);                 // rewinds and re-seeds the work step

        harness.collect(run, 0);                // the rework finishes
        harness.reconcile(run);                 // advance back to the gate
        harness.reconcile(run);                 // the stale verdict must be replaced, not re-read

        assertNotEquals(firstGate, harness.at(run, 1).id,
                "a gate whose input was reworked must judge again, not answer from last round");
        assertEquals(WorkflowRunState.ACTIVE, run.state, "the second judgement has not happened yet");
    }

    // ---- onFail.mode: resume ----

    private static final String RESUMING_GATE = """
            name: rework-resume-under-test
            strategy: rework
            steps:
              - id: work
                template: generic
                task: do the work
              - id: gate
                template: generic
                task: judge the work
                includePreviousResult: true
                onFail:
                  retry: work
                  maxAttempts: 3
                  mode: resume
            """;

    /** Plant the work step, collect it, and plant the resuming gate over it. */
    private WorkflowRun runUpToTheResumingGate() {
        WorkflowRun run = harness.start(RESUMING_GATE);
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.reconcile(run);
        harness.reconcile(run);
        return run;
    }

    @Test
    void resume_mode_re_arms_the_same_task_instead_of_planting_another_one() {
        WorkflowRun run = runUpToTheResumingGate();
        String workTask = harness.at(run, 0).id;
        String session = harness.at(run, 0).sessionId;

        harness.collect(run, 1, "fail");
        harness.reconcile(run);

        assertEquals(workTask, harness.at(run, 0).id, "resume continues the task that is already there");
        assertEquals(session, harness.at(run, 0).sessionId, "the same session is what makes it a resume");
        assertEquals(TaskMode.RESUME, harness.at(run, 0).mode);
        assertEquals(TaskState.PENDING, harness.at(run, 0).state, "the dispatcher relaunches it");
        assertEquals(2, harness.at(run, 0).attempt);
        assertEquals(2, harness.plantedCount(run),
                "a retry would have made a third task here; a resume makes none");
    }

    @Test
    void a_resumed_rework_is_told_what_the_gate_objected_to() {
        WorkflowRun run = runUpToTheResumingGate();
        harness.collect(run, 1, "fail");
        harness.summarise(run, 1, "the function ignores negative input");

        harness.reconcile(run);

        assertTrue(harness.at(run, 0).resumeMessage.contains("the function ignores negative input"),
                "an agent told only that it was rejected has to guess at what for");
    }

    @Test
    void a_resumed_rework_does_not_carry_its_previous_verdict_forward() {
        WorkflowRun run = runUpToTheResumingGate();
        harness.collect(run, 0, "pass");         // the work step's own last answer
        harness.collect(run, 1, "fail");

        harness.reconcile(run);

        assertNull(harness.at(run, 0).verdict,
                "the re-armed task is being sent back to replace that result, not to stand on it");
    }

    @Test
    void a_resumed_rework_is_still_judged_again_rather_than_read_from_last_round() {
        WorkflowRun run = runUpToTheResumingGate();
        String firstGate = harness.at(run, 1).id;
        harness.collect(run, 1, "fail");
        harness.reconcile(run);                 // re-arms the work task in place

        harness.collect(run, 0);                // the resumed agent finishes
        harness.reconcile(run);                 // advance back to the gate
        harness.reconcile(run);

        assertNotEquals(firstGate, harness.at(run, 1).id,
                "the gate must judge the corrected work; a resume moves no creation time, so the "
                        + "staleness rule has to be about when a task was last armed");
    }

    @Test
    void resume_falls_back_to_a_fresh_seed_when_there_is_no_session_to_resume_into() {
        WorkflowRun run = runUpToTheResumingGate();
        String workTask = harness.at(run, 0).id;
        harness.at(run, 0).hostWorkspacePath = null;     // never reached dispatch
        harness.collect(run, 1, "fail");

        harness.reconcile(run);

        assertNotEquals(workTask, harness.at(run, 0).id, "nothing to resume — plant it again");
        assertEquals(TaskMode.WORK, harness.at(run, 0).mode);
    }

    @Test
    void spent_attempts_fail_the_run_rather_than_parking_it_for_an_answer() {
        WorkflowRun run = runUpToTheGate();
        harness.collect(run, 1, "fail");
        harness.reconcile(run);
        harness.collect(run, 0);
        harness.reconcile(run);
        harness.reconcile(run);                 // the gate is planted again

        harness.collect(run, 1, "fail");        // maxAttempts is 2 — this exhausts it
        harness.reconcileUntilSettled(run);

        assertEquals(WorkflowRunState.FAILED, run.state,
                "spent, not stuck: there is no question to answer and no task to re-arm");
    }
}
