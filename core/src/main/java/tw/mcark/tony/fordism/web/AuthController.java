package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.auth.Accounts;
import tw.mcark.tony.fordism.auth.AuthProviderId;
import tw.mcark.tony.fordism.auth.Enrollment;
import tw.mcark.tony.fordism.auth.ExternalAuthProvider;
import tw.mcark.tony.fordism.auth.ExternalIdentity;
import tw.mcark.tony.fordism.auth.Group;
import tw.mcark.tony.fordism.auth.LoginAttempt;
import tw.mcark.tony.fordism.auth.LoginAttempts;
import tw.mcark.tony.fordism.auth.LoginCallback;
import tw.mcark.tony.fordism.auth.PasswordHash;
import tw.mcark.tony.fordism.auth.SeededGroups;
import tw.mcark.tony.fordism.auth.SessionCookie;
import tw.mcark.tony.fordism.auth.User;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tinylog.Logger;

/**
 * {@code /api/auth/*} — the only endpoints that answer without a session, because they are how a
 * session is obtained.
 *
 * <p>The bootstrap is the delicate one. It exists so a fresh install can be claimed by the person
 * holding {@code FORDISM_ADMIN_SECRET}, and it is closed by the existence of any account at all —
 * checked before the secret, so that rotating or leaking the secret afterwards changes nothing.
 */
public final class AuthController {
    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    /**
     * A wrong password costs a full second. It is not a rate limiter, but it turns an online
     * dictionary attack from thousands of guesses a second into one a second, which is the
     * difference between a weekend and a geological age.
     */
    private static final long FAILED_LOGIN_DELAY_MILLIS = 1000;

    private final Accounts accounts;
    private final Map<AuthProviderId, ExternalAuthProvider> providers;
    private final Enrollment enrollment;
    private final LoginAttempts attempts = new LoginAttempts();

    public AuthController(Accounts accounts, Map<AuthProviderId, ExternalAuthProvider> providers,
            Enrollment enrollment) {
        this.accounts = accounts;
        this.providers = providers;
        this.enrollment = enrollment;
    }

    /** What the login screen offers, and whether this install is still unclaimed. */
    public void offered(Context ctx) {
        List<Views.Provider> offered = new ArrayList<>();
        for (AuthProviderId provider : accounts.configuration().providers()) {
            offered.add(new Views.Provider(provider.id()));
        }
        Api.json(ctx, new Views.AuthProviders(offered, accounts.users().isEmpty()));
    }

    /** One-time: claim an install that has no accounts, with the admin secret. */
    public void bootstrap(Context ctx) {
        if (!accounts.users().isEmpty()) {
            // Deliberately before the secret check — once anyone exists this door is shut for good,
            // and answering differently for a right secret would say whether the secret was right.
            Api.fail(ctx, 403, "bootstrap is closed — this instance already has an account");
            return;
        }
        Map<String, Object> body = Api.body(ctx);
        if (!isAdminSecret(Api.string(body.get("secret")))) {
            Logger.warn("rejected a bootstrap attempt with the wrong admin secret");
            Api.fail(ctx, 403, "wrong admin secret");
            return;
        }
        String password = Api.string(body.get("password"));
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            Api.fail(ctx, 400, "the admin password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters");
            return;
        }
        Group admins = accounts.groups().findByName(SeededGroups.ADMINS)
                .orElseThrow(() -> new IllegalStateException("the seeded \"" + SeededGroups.ADMINS
                        + "\" group is missing — it is created at boot, so state is unwritable or edited"));
        User admin;
        try {
            admin = accounts.users().create(User.withPassword(Api.string(body.get("email")),
                    Api.string(body.get("displayName")), PasswordHash.of(password)));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
            return;
        }
        accounts.groups().update(admins.withMember(admin.id()));
        accounts.startSession(ctx, admin);
        Logger.info("bootstrapped the first admin {}", admin.email());
        Api.json(ctx, AccountViews.me(admin, accounts.groups()));
    }

    /** Local sign-in with an email and password. */
    public void login(Context ctx) {
        if (!accounts.configuration().localEnabled()) {
            Api.fail(ctx, 403, "local sign-in is not enabled on this instance");
            return;
        }
        Map<String, Object> body = Api.body(ctx);
        String email = Api.string(body.get("email"));
        User user = accounts.users().findByEmail(email).orElse(null);
        if (user == null || !user.password().map(hash -> hash.matches(Api.string(body.get("password"))))
                .orElse(false)) {
            dampen();
            // One message for both halves: telling an attacker which addresses exist is a gift.
            Api.fail(ctx, 401, "wrong email or password");
            return;
        }
        accounts.startSession(ctx, user);
        Logger.info("{} signed in locally", user.email());
        Api.json(ctx, AccountViews.me(user, accounts.groups()));
    }

    /** Begin an OAuth sign-in: remember the attempt, send the browser to the provider. */
    public void beginExternal(Context ctx) {
        AuthProviderId id = AuthProviderId.from(ctx.pathParam("provider")).orElse(null);
        ExternalAuthProvider provider = id == null ? null : providers.get(id);
        if (provider == null) {
            Api.fail(ctx, 404, "no such sign-in provider: " + ctx.pathParam("provider"));
            return;
        }
        ctx.redirect(provider.authorizationUrl(attempts.start(id)));
    }

    /** Come back from the provider: verify, map to an account, start a session. */
    public void completeExternal(Context ctx) {
        AuthProviderId id = AuthProviderId.from(ctx.pathParam("provider")).orElse(null);
        ExternalAuthProvider provider = id == null ? null : providers.get(id);
        if (provider == null) {
            Api.fail(ctx, 404, "no such sign-in provider: " + ctx.pathParam("provider"));
            return;
        }
        LoginAttempt attempt = attempts.claim(ctx.queryParam("state")).orElse(null);
        if (attempt == null || attempt.provider() != id) {
            // Unknown, reused or stale state: this callback is not one we sent anybody on.
            toLogin(ctx, "invalid_state");
            return;
        }
        String code = ctx.queryParam("code");
        if (code == null || code.isBlank()) {
            toLogin(ctx, "access_denied");
            return;
        }
        ExternalIdentity identity = provider.identify(new LoginCallback(code, attempt)).orElse(null);
        if (identity == null) {
            toLogin(ctx, "exchange_failed");
            return;
        }
        User user = enrollment.resolve(identity).orElse(null);
        if (user == null) {
            toLogin(ctx, "not_allowed");
            return;
        }
        accounts.startSession(ctx, user);
        Logger.info("{} signed in with {}", user.email(), id.id());
        ctx.redirect("/");
    }

    /** End the session server-side and clear the cookie. */
    public void logout(Context ctx) {
        SessionCookie.token(ctx).ifPresent(accounts.sessions()::invalidate);
        SessionCookie.clear(ctx, accounts.configuration());
        ctx.status(204);
    }

    /**
     * Who am I, and what may I do. Outside the gate — it has to be answerable with "nobody", which
     * is how the app knows to show the login screen.
     */
    public void me(Context ctx) {
        User user = accounts.signedIn(ctx).orElse(null);
        if (user == null) {
            Api.fail(ctx, 401, "not signed in");
            return;
        }
        Api.json(ctx, AccountViews.me(user, accounts.groups()));
    }

    private boolean isAdminSecret(String offered) {
        return MessageDigest.isEqual(offered.getBytes(StandardCharsets.UTF_8),
                accounts.configuration().adminSecret().getBytes(StandardCharsets.UTF_8));
    }

    private static void dampen() {
        try {
            Thread.sleep(FAILED_LOGIN_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Send the browser back to the login page with a reason. The codes are the ones the login page
     * has friendly wording for — a code it does not recognise renders as a bare failure, so these
     * spellings are part of the contract, not a log line.
     */
    private static void toLogin(Context ctx, String error) {
        ctx.redirect("/login?error=" + error);
    }
}
