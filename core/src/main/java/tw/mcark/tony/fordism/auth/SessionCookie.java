package tw.mcark.tony.fordism.auth;

import io.javalin.http.Context;
import java.util.Optional;

/**
 * The one cookie Fordism sets.
 *
 * <p>{@code HttpOnly} so a script cannot read the token, {@code SameSite=Lax} so a third-party form
 * post cannot ride it (the {@code X-Fordism-Request} header the gate demands on writes is the
 * second lock on the same door), and {@code Secure} whenever the instance is served over HTTPS —
 * configurable rather than always on, because a Secure cookie is silently dropped over plain HTTP
 * and a local install would look like a login loop with no error anywhere.
 */
public final class SessionCookie {

    public static final String NAME = "fordism_session";

    private SessionCookie() {}

    /** Attach a session to the response. */
    public static void issue(Context ctx, Session session, AuthConfiguration configuration) {
        long seconds = Math.max(0, (session.expiresAt() - System.currentTimeMillis()) / 1000);
        ctx.header("Set-Cookie", attributes(NAME + "=" + session.token(), seconds, configuration));
    }

    /** Expire it — logout, and any failure that must not leave a half-valid cookie behind. */
    public static void clear(Context ctx, AuthConfiguration configuration) {
        ctx.header("Set-Cookie", attributes(NAME + "=", 0, configuration));
    }

    /** The token this request presented, if any. */
    public static Optional<String> token(Context ctx) {
        String value = ctx.cookie(NAME);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String attributes(String pair, long maxAgeSeconds, AuthConfiguration configuration) {
        return pair + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAgeSeconds
                + (configuration.cookieSecure() ? "; Secure" : "");
    }
}
