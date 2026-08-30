package tw.mcark.tony.fordism.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limits failed sign-ins, so the login is more than a one-second speed bump in front of an
 * unlimited guessing machine.
 *
 * <p>Every attempt is checked against two keys — the account being tried and the caller's IP — and
 * a lockout on either one refuses it. Neither key alone is enough: a spread-out attack rotates IPs
 * against one account (the account key catches it), and a single host that stumbled onto a valid
 * username sprays passwords (the IP key catches it). A wrong guess counts against both.
 *
 * <p>In memory on purpose. A lockout that reset on restart is a weakness only if an attacker can
 * restart core, which the login cannot do; sessions must survive a redeploy and so live on disk,
 * a lockout window need not. The one thing kept per key is a count and the time the window opened.
 *
 * <p>The response the caller sees never says which key tripped, or that a key tripped at all — the
 * controller answers the same "wrong email or password" whether the credential was wrong or the
 * account was locked, so this leaks neither which usernames exist nor when to try again.
 */
public final class LoginThrottle {

    /** Failures allowed in a window before the key locks. */
    private static final int THRESHOLD = 5;

    /** How long failures accumulate, and how long a lock lasts once tripped. */
    private static final long WINDOW_MILLIS = 15 * 60 * 1000L;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** Whether this attempt should be refused before the password is even checked. */
    public boolean isLocked(String account, String ip) {
        long now = System.currentTimeMillis();
        return locked(key("a", account), now) || locked(key("i", ip), now);
    }

    /** Record a failed attempt against both keys. Call after a wrong password, never before. */
    public void recordFailure(String account, String ip) {
        long now = System.currentTimeMillis();
        bump(key("a", account), now);
        bump(key("i", ip), now);
    }

    /** A good sign-in clears the account's failures; the IP's stand, since other accounts share it. */
    public void recordSuccess(String account) {
        counters.remove(key("a", account));
    }

    private boolean locked(String key, long now) {
        Counter counter = counters.get(key);
        return counter != null && counter.failures >= THRESHOLD && now - counter.windowStart < WINDOW_MILLIS;
    }

    private void bump(String key, long now) {
        counters.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.windowStart >= WINDOW_MILLIS) {
                return new Counter(now, 1);
            }
            return new Counter(existing.windowStart, existing.failures + 1);
        });
    }

    /** A blank key (no account named, or an IP we could not read) is bucketed together, not skipped. */
    private static String key(String kind, String value) {
        return kind + ":" + (value == null || value.isBlank() ? "?" : value.trim().toLowerCase());
    }

    private record Counter(long windowStart, int failures) {}
}
