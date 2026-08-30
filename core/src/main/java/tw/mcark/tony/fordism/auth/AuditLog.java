package tw.mcark.tony.fordism.auth;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.tinylog.Logger;

/**
 * An append-only record of who did what: every write behind the gate, and every sign-in, bootstrap
 * and token mint. Without it a break-in leaves almost no trail — the state snapshot shows the world
 * as it is now, never who changed it or when.
 *
 * <p>Append-only, one JSON object per line ({@code audit.log}), never rewritten in place. The state
 * snapshot's write-whole-file trick is wrong here: an audit line a later write could erase is not
 * an audit line. Lines are only ever added, and reads take the tail.
 *
 * <p>Bounded by line count, not by rewriting: when the file grows past a cap it is rolled once to
 * {@code audit.log.1}, so a full disk cannot be caused by logging and the trail still survives one
 * roll. What matters is that no code path here can shorten the live file except that atomic roll.
 */
public final class AuditLog {
    private static final Gson GSON = new Gson();
    private static final long MAX_LINES = 200_000;

    private final Path file;

    public AuditLog(Path stateDir) {
        this.file = stateDir.resolve("audit.log");
    }

    /** An allowed action by this actor from this IP — the common case (a sign-in, a successful write). */
    public void record(Actor actor, String ip, String action) {
        append(new Entry(System.currentTimeMillis(), actor.id(), actor.email(), ip, action, true));
    }

    /** A refused action — a write the gate turned down. Recorded so an attempt shows, not just a success. */
    public void recordDenied(Actor actor, String ip, String action) {
        append(new Entry(System.currentTimeMillis(), actor.id(), actor.email(), ip, action, false));
    }

    /**
     * Write one line. Never throws into the caller: a request must not fail because the audit write
     * did — a lost line is bad, a lost operation because of a lost line is worse.
     */
    private synchronized void append(Entry entry) {
        try {
            Files.createDirectories(file.getParent());
            rollIfLarge();
            Files.writeString(file, GSON.toJson(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            Logger.warn("could not write audit entry for {} {}: {}", entry.actor(), entry.action(), e.getMessage());
        }
    }

    /** The most recent {@code limit} entries, newest first. */
    public synchronized List<Entry> tail(int limit) {
        List<Entry> all = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return all;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    all.add(GSON.fromJson(line, Entry.class));
                }
            }
        } catch (IOException | RuntimeException e) {
            Logger.warn("could not read audit log: {}", e.getMessage());
        }
        Collections.reverse(all);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    private void rollIfLarge() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        long lines;
        try (java.util.stream.Stream<String> stream = Files.lines(file)) {
            lines = stream.count();
        }
        if (lines >= MAX_LINES) {
            Files.move(file, file.resolveSibling("audit.log.1"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Who did it: an account id and the email, kept together so callers pass one thing, not two. */
    public record Actor(String id, String email) {}

    /** One audited action. {@code actor} is a user id; {@code allowed} is whether the gate let it through. */
    public record Entry(long at, String actor, String actorEmail, String ip, String action, boolean allowed) {}
}
