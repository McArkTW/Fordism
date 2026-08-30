package tw.mcark.tony.fordism.store;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * What to show from the run history: which runs, in what order, and how far in.
 *
 * <p>A record because there are six of them and a method taking six positional arguments is the
 * thing {@code banStyle} exists to stop — but also because this is the shape that has to survive
 * the move to SQL. Everything here is a WHERE clause, an ORDER BY and a keyset cursor.
 *
 * <p>{@code before} is a keyset cursor, not an offset. Runs arrive at the top of this list, so an
 * offset shifts under a reader and page two silently repeats rows from page one.
 *
 * <p>The cursor is {@code createdAt:id}, not a bare timestamp, because several runs are routinely
 * created in the same millisecond — a fan-out seeds them in a single tick. On a timestamp alone
 * those tie, their order is undefined, and a page boundary landing inside the tie drops or repeats
 * whichever ones fall the wrong side of it. The id breaks the tie and makes the order total.
 */
public record RunQuery(String workflow, Set<WorkflowRunState> states, Long since, String text,
                       boolean oldestFirst, String before, int limit) {

    /** No filter, newest first — what {@code GET /api/runs} answered before it could be queried. */
    public static final int DEFAULT_LIMIT = 200;

    public static RunQuery everything() {
        return new RunQuery(null, Set.of(), null, null, false, null, DEFAULT_LIMIT);
    }

    /** The runs that want attention: in flight, or parked waiting on a human. */
    public static RunQuery live() {
        return new RunQuery(null, Set.of(WorkflowRunState.ACTIVE, WorkflowRunState.ASKED),
                null, null, false, null, DEFAULT_LIMIT);
    }

    public boolean matches(WorkflowRun run) {
        return matchesWorkflow(run) && matchesState(run) && matchesSince(run) && matchesText(run);
    }

    private boolean matchesWorkflow(WorkflowRun run) {
        return workflow == null || workflow.isBlank() || workflow.equals(run.workflowName);
    }

    private boolean matchesState(WorkflowRun run) {
        return states == null || states.isEmpty() || states.contains(run.state);
    }

    private boolean matchesSince(WorkflowRun run) {
        return since == null || run.createdAt >= since;
    }

    /** Free text over the run id and its workflow name — enough to paste an id out of a log. */
    private boolean matchesText(WorkflowRun run) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String needle = text.trim().toLowerCase();
        return run.id.toLowerCase().contains(needle)
                || (run.workflowName != null && run.workflowName.toLowerCase().contains(needle));
    }

    /** Whether this run falls after the cursor, in the same total order the page is sorted by. */
    public boolean afterCursor(WorkflowRun run) {
        if (before == null || before.isBlank()) {
            return true;
        }
        int separator = before.lastIndexOf(':');
        if (separator < 0) {
            return true;   // an unreadable cursor shows the first page rather than nothing
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(before.substring(0, separator));
        } catch (NumberFormatException e) {
            return true;
        }
        String id = before.substring(separator + 1);
        int byTime = Long.compare(run.createdAt, timestamp);
        int compared = byTime != 0 ? byTime : run.id.compareTo(id);
        return oldestFirst ? compared > 0 : compared < 0;
    }

    /** The total order this query pages through: time, then id to break the ties time leaves. */
    public Comparator<WorkflowRun> order() {
        Comparator<WorkflowRun> byTimeThenId =
                Comparator.comparingLong((WorkflowRun run) -> run.createdAt).thenComparing(run -> run.id);
        return oldestFirst ? byTimeThenId : byTimeThenId.reversed();
    }

    public int effectiveLimit() {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, DEFAULT_LIMIT);
    }

    /** The cursor a caller passes back to get the next page, or null when this is the last one. */
    public static String nextCursor(List<WorkflowRun> page, boolean more) {
        if (!more || page.isEmpty()) {
            return null;
        }
        WorkflowRun last = page.get(page.size() - 1);
        return last.createdAt + ":" + last.id;
    }
}
