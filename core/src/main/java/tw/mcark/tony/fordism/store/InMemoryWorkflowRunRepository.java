package tw.mcark.tony.fordism.store;

import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.run.WorkflowRunState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Optional;

public final class InMemoryWorkflowRunRepository implements WorkflowRunRepository {
    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();

    public void save(WorkflowRun r) { runs.put(r.id, r); }
    public Optional<WorkflowRun> find(String id) { return Optional.ofNullable(runs.get(id)); }
    public List<WorkflowRun> all() { return new ArrayList<>(runs.values()); }

    public List<WorkflowRun> query(RunQuery query) {
        return runs.values().stream()
                .filter(query::matches)
                .filter(query::afterCursor)
                .sorted(query.order())
                .limit(query.effectiveLimit() + 1L)   // one extra: its presence is "there is more"
                .collect(Collectors.toList());
    }

    public List<WorkflowRun> active() {
        return runs.values().stream()
                .filter(r -> r.state == WorkflowRunState.ACTIVE)
                .collect(Collectors.toList());
    }
}
