package com.hp.vcosmos.foundry.field;

import com.hp.vcosmos.foundry.config.FoundryConfiguration;
import com.hp.vcosmos.foundry.model.task.TaskState;
import com.hp.vcosmos.foundry.store.TaskRepository;

/** Current field occupancy / capacity gate. */
public final class FieldView {
    private final TaskRepository tasks;
    private final FoundryConfiguration configuration;

    public FieldView(TaskRepository tasks, FoundryConfiguration configuration) {
        this.tasks = tasks;
        this.configuration = configuration;
    }

    public int running() { return tasks.byState(TaskState.RUNNING).size(); }
    public boolean hasCapacity() { return running() < configuration.maximumConcurrentTasks; }
}
