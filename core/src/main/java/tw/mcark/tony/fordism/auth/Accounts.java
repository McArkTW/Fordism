package tw.mcark.tony.fordism.auth;

import io.javalin.http.Context;
import java.util.Optional;

/**
 * The auth layer as one collaborator: the settings, the three stores, and the one question every
 * request starts with — who is this?
 *
 * <p>Wired once in {@code Fordism.main} and handed to the app, so the gate, the auth endpoints and
 * the user/group endpoints all read the same stores rather than each opening the state directory
 * for themselves.
 */
public record Accounts(AuthConfiguration configuration, UserStore users, GroupStore groups,
                       SessionStore sessions) {

    /** The account behind this request's session cookie, or empty when it has none or a stale one. */
    public Optional<User> signedIn(Context ctx) {
        return SessionCookie.token(ctx)
                .flatMap(sessions::find)
                .flatMap(session -> users.find(session.userId()));
    }

    /** Sign this account in: a fresh session, attached to the response. */
    public Session startSession(Context ctx, User user) {
        Session session = sessions.create(user.id());
        SessionCookie.issue(ctx, session, configuration);
        return session;
    }
}
