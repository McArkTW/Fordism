package com.hp.vcosmos.foundry.model.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a gate, a branch predicate and a reconciler loop all ask a finished task. */
class TaskVerdictTest {

    private static Task task(String verdict, String summary) {
        Task task = new Task("task-1", "run-1", 0, "session-1");
        task.verdict = verdict;
        task.summary = summary;
        return task;
    }

    @Test
    void an_explicit_verdict_decides_it_whatever_the_summary_says() {
        assertTrue(task("fail", "Everything looks great.").reports("fail"));
        assertFalse(task("pass", "One failure was found and fixed.").reports("fail"));
    }

    @Test
    void a_verdict_is_matched_whole_and_case_insensitively() {
        assertTrue(task("PASS", "").reports("pass"));
        assertTrue(task("  done  ", "").reports("done"));
        assertFalse(task("passed-with-notes", "").reports("pass"));
    }

    @Test
    void a_passing_gate_that_mentions_failure_in_prose_is_why_the_field_exists() {
        // The bug: no verdict, and "no failures found" contains "fail", so the gate reworked
        // work it had just approved.
        assertTrue(task(null, "No failures found.").reports("fail"));
        assertFalse(task("pass", "No failures found.").reports("fail"));
    }

    @Test
    void without_a_verdict_it_falls_back_to_the_summary_unchanged() {
        assertTrue(task(null, "FAILED: the function returns the wrong type").reports("fail"));
        assertTrue(task("", "the goal is done").reports("done"));
        assertFalse(task(null, "still working on it").reports("done"));
        assertFalse(task(null, null).reports("done"));
    }
}
