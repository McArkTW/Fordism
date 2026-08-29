package tw.mcark.tony.fordism.auth;

import com.google.gson.Gson;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code before("/api/*")} handler: nothing under {@code /api} answers until this says who is
 * calling and that they are allowed to.
 *
 * <p>Exactly three paths are exempt, and each for a stated reason: {@code /api/health} and
 * {@code /api/version} are what a load balancer and a deploy script poll before anyone has logged
 * in, and {@code /api/auth/*} is how you log in at all.
 *
 * <p>A write also has to carry {@code X-Fordism-Request}. The session cookie is {@code SameSite=Lax},
 * which a browser will still attach to a top-level navigation — a form on another site cannot set a
 * custom header, so requiring one turns "somebody clicked a link" back into "somebody used the app".
 */
public final class AuthGate {

    /** Where the signed-in user is parked for the handlers behind the gate. */
    public static final String USER_ATTRIBUTE = "fordism.user";

    /** Any value will do — that it can be set at all is the proof, not what it says. */
    public static final String REQUEST_HEADER = "X-Fordism-Request";

    private static final Set<String> OPEN_PATHS = Set.of("/api/health", "/api/version");
    private static final String AUTH_PREFIX = "/api/auth/";
    private static final Gson GSON = new Gson();

    private final Accounts accounts;

    public AuthGate(Accounts accounts) {
        this.accounts = accounts;
    }

    /** Register as {@code app.before("/api/*", gate::guard)}. */
    public void guard(Context ctx) {
        String path = ctx.path();
        if (OPEN_PATHS.contains(path) || path.startsWith(AUTH_PREFIX)) {
            return;
        }
        User user = accounts.signedIn(ctx).orElse(null);
        if (user == null) {
            refuse(ctx, 401, "not signed in");
            return;
        }
        if (isWrite(ctx.method()) && ctx.header(REQUEST_HEADER) == null) {
            refuse(ctx, 403, "a write must carry the " + REQUEST_HEADER + " header");
            return;
        }
        Permission required = RoutePermissions.required(ctx.method(), path).orElse(null);
        if (required == null) {
            refuse(ctx, 403, "no permission is declared for " + ctx.method() + " " + path);
            return;
        }
        if (!accounts.groups().allows(user.id(), required)) {
            refuse(ctx, 403, "this account does not have " + required.id());
            return;
        }
        ctx.attribute(USER_ATTRIBUTE, user);
    }

    /** The user the gate admitted, for a handler that needs to know whose request this is. */
    public static Optional<User> currentUser(Context ctx) {
        return Optional.ofNullable(ctx.<User>attribute(USER_ATTRIBUTE));
    }

    private static boolean isWrite(HandlerType method) {
        return method == HandlerType.POST || method == HandlerType.PUT || method == HandlerType.DELETE;
    }

    private static void refuse(Context ctx, int status, String reason) {
        ctx.status(status).contentType("application/json")
                .result(GSON.toJson(Map.of("error", reason)));
        ctx.skipRemainingHandlers();
    }
}
