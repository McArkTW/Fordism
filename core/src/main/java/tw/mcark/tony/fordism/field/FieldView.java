package tw.mcark.tony.fordism.field;

import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.model.task.TaskState;
import tw.mcark.tony.fordism.store.TaskRepository;

/** Current field occupancy / capacity gate. */
public final class FieldView {
    private final TaskRepository tasks;
    private final FordismConfiguration configuration;

    public FieldView(TaskRepository tasks, FordismConfiguration configuration) {
        this.tasks = tasks;
        this.configuration = configuration;
    }

    public int running() { return tasks.byState(TaskState.RUNNING).size(); }
    public boolean hasCapacity() { return running() < configuration.maximumConcurrentTasks; }
}
