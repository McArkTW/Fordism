package tw.mcark.tony.fordism.auth;

import io.javalin.http.Context;
import java.util.Optional;

/**
 * The auth layer as one collaborator: the settings, the four stores, and the one question every
 * request starts with — who is this?
 *
 * <p>Wired once in {@code Fordism.main} and handed to the app, so the gate, the auth endpoints and
 * the user/group endpoints all read the same stores rather than each opening the state directory
 * for themselves.
 */
public record Accounts(AuthConfiguration configuration, UserStore users, GroupStore groups,
                       SessionStore sessions, ApiTokenStore apiTokens, AuditLog audit) {

    private static final String BEARER = "Bearer ";

    /**
     * Who is behind this request, and by what means — an API token if it presented one, otherwise
     * its session cookie.
     *
     * <p>The token is tried first so an explicit {@code Authorization} header always wins. A script
     * run from a browser-authenticated machine would otherwise silently act as the cookie's owner
     * with the cookie's full permissions, which is precisely the narrowing a token exists to do.
     */
    public Optional<Caller> caller(Context ctx) {
        Optional<Caller> bearer = bearing(ctx);
        if (bearer.isPresent()) {
            return bearer;
        }
        return SessionCookie.token(ctx)
                .flatMap(sessions::find)
                .flatMap(session -> users.find(session.userId()))
                .map(Caller::session);
    }

    /** The account behind this request, however it authenticated. */
    public Optional<User> signedIn(Context ctx) {
        return caller(ctx).map(Caller::user);
    }

    /** Sign this account in: a fresh session, attached to the response. */
    public Session startSession(Context ctx, User user) {
        Session session = sessions.create(user.id());
        SessionCookie.issue(ctx, session, configuration);
        return session;
    }

    /**
     * The caller an {@code Authorization: Bearer} header proves, if any.
     *
     * <p>A token whose owner no longer exists authenticates nobody — deleting an account revokes
     * its tokens, and this is the second lock on that door for a row that somehow outlived it.
     */
    private Optional<Caller> bearing(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        ApiToken token = apiTokens.find(header.substring(BEARER.length()).trim()).orElse(null);
        if (token == null) {
            return Optional.empty();
        }
        return users.find(token.userId()).map(user -> {
            apiTokens.touch(token);
            return Caller.bearing(user, token);
        });
    }
}
