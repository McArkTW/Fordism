package com.hp.vcosmos.foundry.store;

import com.google.gson.Gson;
import com.hp.vcosmos.foundry.config.FoundryConfiguration;
import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import com.hp.vcosmos.foundry.model.task.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.tinylog.Logger;

/** Durable JSON snapshot of runs + tasks on the host workspaces mount — survives redeploy. */
public final class JsonStateStore {
    private static final Gson GSON = new Gson();
    private final Path file;

    public JsonStateStore(FoundryConfiguration configuration) {
        this.file = Paths.get(configuration.stateDir, "state.json");
    }

    public synchronized void snapshot(List<WorkflowRun> runs, List<Task> tasks) {
        try {
            Files.createDirectories(file.getParent());
            State state = new State();
            state.runs = runs;
            state.tasks = tasks;
            Files.writeString(file, GSON.toJson(state));
        } catch (Exception e) {
            Logger.error(e, "state snapshot failed");
        }
    }

    public State restore() {
        try {
            if (!Files.exists(file)) {
                return new State();
            }
            State state = GSON.fromJson(Files.readString(file), State.class);
            return state == null ? new State() : state;
        } catch (Exception e) {
            Logger.error(e, "state restore failed");
            return new State();
        }
    }

    /** Serialized shape. */
    public static final class State {
        public List<WorkflowRun> runs = new ArrayList<>();
        public List<Task> tasks = new ArrayList<>();
    }
}
