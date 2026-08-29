package tw.mcark.tony.fordism.store;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.config.FordismConfiguration;
import tw.mcark.tony.fordism.model.run.WorkflowRun;
import tw.mcark.tony.fordism.model.task.Task;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.tinylog.Logger;

/** Durable JSON snapshot of runs + tasks on the host workspaces mount — survives redeploy. */
public final class JsonStateStore {
    private static final Gson GSON = new Gson();
    private final Path file;

    public JsonStateStore(FordismConfiguration configuration) {
        this(Paths.get(configuration.stateDir, "state.json"));
    }

    /** The snapshot file itself — the seam a test writes to a temp dir through. */
    public JsonStateStore(Path file) {
        this.file = file;
    }

    public synchronized void snapshot(List<WorkflowRun> runs, List<Task> tasks) {
        try {
            Files.createDirectories(file.getParent());
            State state = new State();
            state.runs = runs;
            state.tasks = tasks;
            writeAtomically(GSON.toJson(state));
        } catch (Exception e) {
            Logger.error(e, "state snapshot failed");
        }
    }

    /**
     * Write to a sibling temp file, flush it to disk, then move it onto the snapshot.
     *
     * <p>The engine snapshots on every tick (~2s), and writing the live file in place truncates it
     * before it is refilled: a crash, a kill or a full disk inside that window leaves a zero-length
     * or half-written state.json, which restores as no runs and no tasks at all. A move means a
     * reader only ever sees one whole file — the previous snapshot or this one.
     *
     * <p>The temp file is a SIBLING because ATOMIC_MOVE only holds within a filesystem, and the
     * state directory is a bind mount. Some filesystems refuse ATOMIC_MOVE outright, so fall back
     * to a plain replace (same precedent as CredentialStore's non-POSIX permission fallback) —
     * still better than truncating the live file, since the temp file is already complete.
     */
    private void writeAtomically(String json) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);   // the bytes must be on disk before the move, or the move outruns them
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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
