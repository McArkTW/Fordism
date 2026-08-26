package tw.mcark.tony.fordism.store;

import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Optional;

public final class InMemoryTaskRepository implements TaskRepository {
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public void save(Task t) { tasks.put(t.id, t); }
    public Optional<Task> find(String id) { return Optional.ofNullable(tasks.get(id)); }
    public List<Task> all() { return new ArrayList<>(tasks.values()); }

    /** The LATEST attempt at that step — a rework seeds a new task at the same index. */
    public Optional<Task> byRunStep(String runId, int step) {
        return tasks.values().stream()
                .filter(t -> t.runId.equals(runId) && t.stepIndex == step)
                .max(Comparator.comparingInt(t -> t.attempt));
    }

    public List<Task> byRun(String runId) {
        return tasks.values().stream().filter(t -> t.runId.equals(runId)).collect(Collectors.toList());
    }

    public List<Task> byState(TaskState state) {
        return tasks.values().stream().filter(t -> t.state == state).collect(Collectors.toList());
    }
}
