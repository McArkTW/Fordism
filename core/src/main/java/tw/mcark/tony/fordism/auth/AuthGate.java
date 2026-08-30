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
 * <p>A write from a BROWSER also has to carry {@code X-Fordism-Request}. The session cookie is
 * {@code SameSite=Lax}, which a browser will still attach to a top-level navigation — a form on
 * another site cannot set a custom header, so requiring one turns "somebody clicked a link" back
 * into "somebody used the app". A request bearing an API token is exempt: nothing attaches an
 * {@code Authorization} header on a victim's behalf, so there is no cross-site request to forge,
 * and demanding the header would make every {@code curl} carry a word that means nothing to it.
 */
public final class AuthGate {

    /** Where the signed-in user is parked for the handlers behind the gate. */
    public static final String USER_ATTRIBUTE = "fordism.user";

    /** Where the whole caller — account plus the token it used, if any — is parked. */
    public static final String CALLER_ATTRIBUTE = "fordism.caller";

    /** Any value will do — that it can be set at all is the proof, not what it says. */
    public static final String REQUEST_HEADER = "X-Fordism-Request";

    private static final Set<String> OPEN_PATHS = Set.of("/api/health", "/api/version");
    private static final String AUTH_PREFIX = "/api/auth/";

    /**
     * Minting and revoking API tokens is closed to API tokens.
     *
     * <p>A leaked token that could mint another would be a leak with no end: revoking the one that
     * got out would not revoke the ones it made, and the trail back to the person would be a chain
     * of tokens rather than a sign-in. Managing tokens takes a browser session.
     */
    private static final String TOKEN_PREFIX = "/api/api-tokens";

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
        Caller caller = accounts.caller(ctx).orElse(null);
        if (caller == null) {
            refuse(ctx, 401, "not signed in");
            return;
        }
        if (caller.isToken() && path.startsWith(TOKEN_PREFIX)) {
            refuse(ctx, 403, "an API token cannot manage API tokens — sign in to do that");
            return;
        }
        if (isWrite(ctx.method()) && !caller.isToken() && ctx.header(REQUEST_HEADER) == null) {
            refuse(ctx, 403, "a write must carry the " + REQUEST_HEADER + " header");
            return;
        }
        Permission required = RoutePermissions.required(ctx.method(), path).orElse(null);
        if (required == null) {
            refuse(ctx, 403, "no permission is declared for " + ctx.method() + " " + path);
            return;
        }
        if (!caller.allows(accounts.groups(), required)) {
            auditWrite(ctx, caller, false);
            refuse(ctx, 403, refusalFor(caller, required));
            return;
        }
        auditWrite(ctx, caller, true);
        ctx.attribute(USER_ATTRIBUTE, caller.user());
        ctx.attribute(CALLER_ATTRIBUTE, caller);
    }

    /** The user the gate admitted, for a handler that needs to know whose request this is. */
    public static Optional<User> currentUser(Context ctx) {
        return Optional.ofNullable(ctx.<User>attribute(USER_ATTRIBUTE));
    }

    /** The caller the gate admitted, for a handler that also cares how they authenticated. */
    public static Optional<Caller> currentCaller(Context ctx) {
        return Optional.ofNullable(ctx.<Caller>attribute(CALLER_ATTRIBUTE));
    }

    /**
     * Which half of the intersection said no. Worth the branch: "your account has it but this token
     * does not" is a five-second fix, and without it the answer is the same sentence a genuine
     * permissions problem gives, which is a support ticket.
     */
    private static String refusalFor(Caller caller, Permission required) {
        if (caller.isToken()) {
            return "this API token does not have " + required.id()
                    + " (a token can only ever hold fewer permissions than the account that made it)";
        }
        return "this account does not have " + required.id();
    }

    /**
     * Audit an authenticated write — the state-changing verbs, allowed or refused. Reads are not
     * logged (they change nothing and would bury the trail), and an unauthenticated caller has no
     * identity worth a line; those are the 401s the gate already turns away.
     */
    private void auditWrite(Context ctx, Caller caller, boolean allowed) {
        if (!isWrite(ctx.method())) {
            return;
        }
        String action = ctx.method() + " " + ctx.path() + (caller.isToken() ? " via token" : "");
        AuditLog.Actor actor = new AuditLog.Actor(caller.user().id(), caller.user().email());
        if (allowed) {
            accounts.audit().record(actor, ctx.ip(), action);
        } else {
            accounts.audit().recordDenied(actor, ctx.ip(), action);
        }
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
