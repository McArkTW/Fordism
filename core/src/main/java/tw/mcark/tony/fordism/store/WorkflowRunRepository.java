package tw.mcark.tony.fordism.store;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import java.util.List;
import java.util.Optional;

/** Workflow-run persistence + lookup. (In-memory now; the Postgres swap implements this.) */
public interface WorkflowRunRepository {
    void save(WorkflowRun r);
    Optional<WorkflowRun> find(String id);
    List<WorkflowRun> active();
    List<WorkflowRun> all();

    /**
     * The runs a reconciler spawned from this one. Its own method rather than a filter over
     * {@link #all()} because it is a WHERE on an indexed column in the store that replaces this,
     * and because a parent waiting on its children asks this on every tick.
     */
    List<WorkflowRun> children(String parentRunId);

    /**
     * One page of the history, ordered and filtered. The predicate lives here rather than in the
     * controller because this is the method a SQL store replaces — it is a WHERE, an ORDER BY and
     * a keyset cursor, already shaped like the query it will become.
     */
    List<WorkflowRun> query(RunQuery query);
}
