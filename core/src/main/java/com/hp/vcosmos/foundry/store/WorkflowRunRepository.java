package com.hp.vcosmos.foundry.store;

import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import java.util.List;
import java.util.Optional;

/** Workflow-run persistence + lookup. (In-memory now; the Postgres swap implements this.) */
public interface WorkflowRunRepository {
    void save(WorkflowRun r);
    Optional<WorkflowRun> find(String id);
    List<WorkflowRun> active();
    List<WorkflowRun> all();

    /**
     * One page of the history, ordered and filtered. The predicate lives here rather than in the
     * controller because this is the method a SQL store replaces — it is a WHERE, an ORDER BY and
     * a keyset cursor, already shaped like the query it will become.
     */
    List<WorkflowRun> query(RunQuery query);
}
