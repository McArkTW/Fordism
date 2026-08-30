package tw.mcark.tony.fordism.auth;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Live sessions, as one JSON array at {@code <stateDir>/sessions.json}.
 *
 * <p>On disk, not in memory: core restarts on every redeploy, and an in-memory session table would
 * log every operator out each time — which trains people to keep a tab open on a login form.
 *
 * <p>The token is 256 bits from {@link SecureRandom} and carries no meaning. Expired rows are
 * dropped whenever the file is written, so the file cannot grow without bound.
 */
public final class SessionStore {
    private static final Type TYPE = new TypeToken<List<Session>>() {}.getType();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    /** How long a browser stays signed in. Long enough to be usable, short enough to matter. */
    public static final Duration LIFETIME = Duration.ofDays(7);

    private final JsonRecordFile<Session> file;

    public SessionStore(Path stateDir) {
        this.file = new JsonRecordFile<>(stateDir.resolve("sessions.json"), TYPE);
    }

    /** Start a session for this account and persist it. */
    public synchronized Session create(String userId) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        Session session = new Session(Base64.getUrlEncoder().withoutPadding().encodeToString(raw),
                userId, System.currentTimeMillis() + LIFETIME.toMillis());
        List<Session> live = live();
        live.add(session);
        file.write(live);
        return session;
    }

    /** The session this token names, or empty when there is none or it has expired. */
    public synchronized Optional<Session> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        return file.read().stream()
                .filter(session -> token.equals(session.token()) && session.isLiveAt(now))
                .findFirst();
    }

    /** End one session — what logout does. */
    public synchronized void invalidate(String token) {
        List<Session> kept = live();
        if (kept.removeIf(session -> session.token().equals(token))) {
            file.write(kept);
        }
    }

    /** End every session an account holds — what deleting or locking that account does. */
    public synchronized void invalidateUser(String userId) {
        List<Session> kept = live();
        if (kept.removeIf(session -> session.userId().equals(userId))) {
            file.write(kept);
        }
    }

    private List<Session> live() {
        long now = System.currentTimeMillis();
        List<Session> kept = new ArrayList<>();
        for (Session session : file.read()) {
            if (session.isLiveAt(now)) {
                kept.add(session);
            }
        }
        return kept;
    }
}
