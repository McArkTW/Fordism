package tw.mcark.tony.fordism.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The OAuth redirects currently in flight, keyed by state.
 *
 * <p>In memory on purpose, unlike sessions: an attempt lives for the seconds between "sign in with
 * Google" and the callback, and a restart in that window should send the person back to the login
 * page rather than resume a half-finished handshake.
 */
public final class LoginAttempts {

    private final Map<String, LoginAttempt> pending = new ConcurrentHashMap<>();

    /** Begin an attempt and remember it. */
    public LoginAttempt start(AuthProviderId provider) {
        forgetExpired();
        LoginAttempt attempt = LoginAttempt.starting(provider);
        pending.put(attempt.state(), attempt);
        return attempt;
    }

    /**
     * Take the attempt this state names, removing it. Empty when the state is unknown, already
     * used or stale — each of which means this callback is not one we started.
     */
    public Optional<LoginAttempt> claim(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        LoginAttempt attempt = pending.remove(state);
        return attempt != null && attempt.isLiveAt(System.currentTimeMillis())
                ? Optional.of(attempt)
                : Optional.empty();
    }

    private void forgetExpired() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(attempt -> !attempt.isLiveAt(now));
    }
}
