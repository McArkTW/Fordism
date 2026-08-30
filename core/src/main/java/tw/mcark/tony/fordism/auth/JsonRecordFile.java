package tw.mcark.tony.fordism.auth;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One JSON array of records on disk, read and written whole.
 *
 * <p>Users, groups and sessions are all small, mutually-referencing lists, so a file per record
 * (the shape credentials and agent-profiles use) would buy nothing and make "is this the last
 * group granting {@code *}" a directory scan.
 *
 * <p>Every failure is thrown with the path in the message. The store this replaced caught
 * {@code Exception} and started empty, which turned an unreadable {@code users.json} into an
 * install with no accounts — and an install with no accounts reopens the one-time admin bootstrap
 * to whoever asks first.
 */
final class JsonRecordFile<T> {
    private static final Gson GSON = new Gson();

    private final Path file;
    private final Type type;

    JsonRecordFile(Path file, Type type) {
        this.file = file;
        this.type = type;
    }

    Path path() {
        return file;
    }

    /** Everything the file holds; an empty list when it does not exist yet. */
    List<T> read() {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file
                    + " — refusing to continue as if it held nothing", e);
        }
        try {
            List<T> loaded = GSON.fromJson(text, type);
            return loaded == null ? new ArrayList<>() : new ArrayList<>(loaded);
        } catch (JsonParseException e) {
            throw new IllegalStateException(file + " is not readable JSON — fix or remove it", e);
        }
    }

    /** Replace the file's contents. Written beside it and moved, so a crash never truncates it. */
    void write(List<T> records) {
        try {
            Files.createDirectories(file.getParent());
            Path staged = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(staged, GSON.toJson(records, type));
            move(staged);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }

    private void move(Path staged) throws IOException {
        try {
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
