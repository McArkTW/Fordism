package com.hp.vcosmos.foundry.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import com.hp.vcosmos.foundry.model.run.WorkflowRunState;
import com.hp.vcosmos.foundry.model.workflow.Strategy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Filtering and paging the history — including the part that stops page two repeating page one. */
class RunQueryTest {

    private static final InMemoryWorkflowRunRepository REPOSITORY = seeded();

    private static InMemoryWorkflowRunRepository seeded() {
        InMemoryWorkflowRunRepository repository = new InMemoryWorkflowRunRepository();
        // createdAt is set on construction, so order is controlled by insertion here.
        repository.save(run("r1", "qc-linear", WorkflowRunState.DONE));
        repository.save(run("r2", "qc-graph", WorkflowRunState.FAILED));
        repository.save(run("r3", "qc-linear", WorkflowRunState.ASKED));
        repository.save(run("r4", "qc-ask", WorkflowRunState.ACTIVE));
        return repository;
    }

    private static WorkflowRun run(String id, String workflow, WorkflowRunState state) {
        WorkflowRun run = new WorkflowRun(id, workflow, Strategy.LINEAR, Map.of());
        run.state = state;
        return run;
    }

    private static List<String> ids(List<WorkflowRun> runs) {
        return runs.stream().map(run -> run.id).toList();
    }

    @Test
    void everything_returns_every_run_newest_first() {
        List<WorkflowRun> all = REPOSITORY.query(RunQuery.everything());
        assertEquals(4, all.size());
        assertEquals("r4", all.get(0).id);   // newest
        assertEquals("r1", all.get(3).id);   // oldest
    }

    @Test
    void a_workflow_filter_narrows_to_that_workflow() {
        RunQuery query = new RunQuery("qc-linear", Set.of(), null, null, false, null, 50);
        assertEquals(List.of("r3", "r1"), ids(REPOSITORY.query(query)));
    }

    @Test
    void a_state_filter_takes_more_than_one_state_because_live_needs_two() {
        RunQuery query = new RunQuery(null, Set.of(WorkflowRunState.ACTIVE, WorkflowRunState.ASKED),
                null, null, false, null, 50);
        assertEquals(List.of("r4", "r3"), ids(REPOSITORY.query(query)));
    }

    @Test
    void free_text_matches_the_run_id_or_the_workflow_name() {
        assertEquals(List.of("r2"),
                ids(REPOSITORY.query(new RunQuery(null, Set.of(), null, "r2", false, null, 50))));
        assertEquals(List.of("r4"),
                ids(REPOSITORY.query(new RunQuery(null, Set.of(), null, "qc-ask", false, null, 50))));
        assertTrue(REPOSITORY.query(new RunQuery(null, Set.of(), null, "nothing", false, null, 50)).isEmpty());
    }

    @Test
    void the_live_query_is_in_flight_and_parked_work() {
        assertEquals(List.of("r4", "r3"), ids(REPOSITORY.query(RunQuery.live())));
    }

    @Test
    void paging_by_cursor_never_repeats_a_row() {
        RunQuery first = new RunQuery(null, Set.of(), null, null, false, null, 2);
        List<WorkflowRun> page = REPOSITORY.query(first);
        assertTrue(page.size() > first.effectiveLimit(), "the extra row signals there is more");

        List<WorkflowRun> shown = page.subList(0, first.effectiveLimit());
        String cursor = RunQuery.nextCursor(shown, true);

        List<WorkflowRun> next = REPOSITORY.query(
                new RunQuery(null, Set.of(), null, null, false, cursor, 2));
        assertFalse(ids(next).stream().anyMatch(ids(shown)::contains), "page two repeated page one");
    }

    @Test
    void the_limit_is_capped_so_one_caller_cannot_ask_for_everything() {
        assertEquals(RunQuery.DEFAULT_LIMIT,
                new RunQuery(null, Set.of(), null, null, false, null, 100_000).effectiveLimit());
        assertEquals(RunQuery.DEFAULT_LIMIT,
                new RunQuery(null, Set.of(), null, null, false, null, 0).effectiveLimit());
    }

    @Test
    void a_cursor_with_no_further_pages_is_null() {
        assertNull(RunQuery.nextCursor(List.of(), false));
        assertNull(RunQuery.nextCursor(REPOSITORY.query(RunQuery.everything()), false));
    }

    @Test
    void runs_created_in_the_same_millisecond_still_have_one_definite_order() {
        // A fan-out seeds several runs in a single tick, so identical createdAt is routine, not an
        // edge case. Without the id tiebreaker their order is undefined and a page boundary landing
        // inside the tie drops or repeats rows.
        List<String> once = ids(REPOSITORY.query(RunQuery.everything()));
        List<String> twice = ids(REPOSITORY.query(RunQuery.everything()));
        assertEquals(once, twice);
        assertEquals(List.of("r4", "r3", "r2", "r1"), once);
    }

    @Test
    void an_unreadable_cursor_shows_the_first_page_rather_than_nothing() {
        RunQuery nonsense = new RunQuery(null, Set.of(), null, null, false, "not-a-cursor", 50);
        assertEquals(4, REPOSITORY.query(nonsense).size());
    }
}
