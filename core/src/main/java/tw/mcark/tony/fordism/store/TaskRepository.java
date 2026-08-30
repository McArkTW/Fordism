package tw.mcark.tony.fordism.store;

import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import java.util.List;
import java.util.Optional;

/** Task persistence + lookup. (In-memory now; the Postgres swap implements this.) */
public interface TaskRepository {
    void save(Task t);
    Optional<Task> find(String id);
    List<Task> all();
    Optional<Task> byRunStep(String runId, int step);
    List<Task> byRun(String runId);
    List<Task> byState(TaskState state);
}
