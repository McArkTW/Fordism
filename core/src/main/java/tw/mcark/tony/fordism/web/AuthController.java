package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.auth.Accounts;
import tw.mcark.tony.fordism.auth.AuditLog;
import tw.mcark.tony.fordism.auth.AuthGate;
import tw.mcark.tony.fordism.auth.AuthProviderId;
import tw.mcark.tony.fordism.auth.Enrollment;
import tw.mcark.tony.fordism.auth.ExternalAuthProvider;
import tw.mcark.tony.fordism.auth.ExternalIdentity;
import tw.mcark.tony.fordism.auth.Group;
import tw.mcark.tony.fordism.auth.LoginAttempt;
import tw.mcark.tony.fordism.auth.LoginAttempts;
import tw.mcark.tony.fordism.auth.LoginCallback;
import tw.mcark.tony.fordism.auth.LoginThrottle;
import tw.mcark.tony.fordism.auth.Mfa;
import tw.mcark.tony.fordism.auth.PasswordHash;
import tw.mcark.tony.fordism.auth.Totp;
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
    private final LoginThrottle throttle = new LoginThrottle();
    /** TOTP secrets generated but not yet confirmed, by user id — in memory; a restart just re-enrols. */
    private final Map<String, String> pendingTotp = new java.util.concurrent.ConcurrentHashMap<>();

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
        // The admin secret is one high-value guess target with no account name, so it is throttled
        // by IP alone under a fixed key. Same one-second dampen as login on top.
        String ip = clientIp(ctx);
        if (throttle.isLocked("bootstrap", ip)) {
            dampen();
            Api.fail(ctx, 403, "too many attempts — wait and try again");
            return;
        }
        Map<String, Object> body = Api.body(ctx);
        if (!isAdminSecret(Api.string(body.get("secret")))) {
            throttle.recordFailure("bootstrap", ip);
            dampen();
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
        accounts.audit().record(new AuditLog.Actor(admin.id(), admin.email()), ip, "bootstrap first admin");
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
        String ip = clientIp(ctx);
        // Locked out is answered exactly like a wrong password — same status, same words — so this
        // reveals neither that the account exists nor that guessing has been throttled.
        if (throttle.isLocked(email, ip)) {
            dampen();
            Api.fail(ctx, 401, "wrong email or password");
            return;
        }
        User user = accounts.users().findByEmail(email).orElse(null);
        if (user == null || !user.password().map(hash -> hash.matches(Api.string(body.get("password"))))
                .orElse(false)) {
            throttle.recordFailure(email, ip);
            dampen();
            // One message for both halves: telling an attacker which addresses exist is a gift.
            Api.fail(ctx, 401, "wrong email or password");
            return;
        }
        // A correct password is not the whole key once a second factor is on. A missing or wrong
        // code counts as a failed attempt — the throttle covers guessing the six digits too — and
        // the "mfaRequired" answer is only reachable after the password already verified, so it
        // reveals nothing to someone who does not hold it.
        if (user.mfaEnabled() && !secondFactorHolds(user, body)) {
            throttle.recordFailure(email, ip);
            dampen();
            if (Api.string(body.get("code")).isBlank() && Api.string(body.get("recoveryCode")).isBlank()) {
                ctx.status(401).contentType("application/json").result("{\"mfaRequired\":true}");
            } else {
                Api.fail(ctx, 401, "wrong code");
            }
            return;
        }
        throttle.recordSuccess(email);
        accounts.startSession(ctx, user);
        accounts.audit().record(new AuditLog.Actor(user.id(), user.email()), ip, "sign-in (local)");
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
        accounts.audit().record(new AuditLog.Actor(user.id(), user.email()), clientIp(ctx), "sign-in (" + id.id() + ")");
        Logger.info("{} signed in with {}", user.email(), id.id());
        ctx.redirect("/");
    }

    /**
     * Begin TOTP enrolment: generate a secret, return it and the {@code otpauth://} URI to scan.
     * The secret is held pending in memory, keyed by user, until a code confirms the phone has it —
     * so an interrupted enrolment leaves no half-enabled factor, and a restart just means starting
     * over. Nothing is written to the account until {@link #confirmMfa}.
     */
    public void beginMfa(Context ctx) {
        User user = selfService(ctx).orElse(null);
        if (user == null) {
            return;
        }
        if (user.mfaEnabled()) {
            Api.fail(ctx, 409, "this account already has a second factor — remove it before enrolling another");
            return;
        }
        String secret = Totp.newSecret();
        pendingTotp.put(user.id(), secret);
        Api.json(ctx, Map.of("secret", secret,
                "otpauthUri", Totp.provisioningUri(secret, user.email(), "Fordism")));
    }

    /**
     * Confirm enrolment with a code from the app, enable the factor, and return the recovery codes
     * ONCE. Requiring a working code before enabling is what stops a mistyped or unscanned secret
     * from locking the person out of their own account.
     */
    public void confirmMfa(Context ctx) {
        User user = selfService(ctx).orElse(null);
        if (user == null) {
            return;
        }
        String secret = pendingTotp.get(user.id());
        if (secret == null) {
            Api.fail(ctx, 409, "no enrolment in progress — start one first");
            return;
        }
        if (!Totp.verify(secret, Api.string(Api.body(ctx).get("code")))) {
            Api.fail(ctx, 400, "that code did not match — check the time on your device and try again");
            return;
        }
        Mfa.Enrolled enrolled = Mfa.enrol(secret);
        accounts.users().update(user.withMfa(enrolled.mfa()));
        pendingTotp.remove(user.id());
        accounts.audit().record(new AuditLog.Actor(user.id(), user.email()), clientIp(ctx), "enable second factor");
        Logger.info("{} enrolled a second factor", user.email());
        Api.json(ctx, Map.of("recoveryCodes", enrolled.recoveryCodes()));
    }

    /**
     * Turn off the second factor. Re-checks the password (or a current code) first: a walk-up at an
     * unlocked screen must not be able to strip MFA off the account, and this endpoint is exempt
     * from the CSRF header the gate adds, so it demands a fresh proof of the person itself.
     */
    public void disableMfa(Context ctx) {
        User user = selfService(ctx).orElse(null);
        if (user == null) {
            return;
        }
        if (!user.mfaEnabled()) {
            ctx.status(204);
            return;
        }
        Map<String, Object> body = Api.body(ctx);
        boolean byPassword = user.password()
                .map(hash -> hash.matches(Api.string(body.get("password")))).orElse(false);
        boolean byCode = user.mfa().verifyTotp(Api.string(body.get("code")));
        if (!byPassword && !byCode) {
            Api.fail(ctx, 403, "confirm your password or a current code to turn off two-factor");
            return;
        }
        accounts.users().update(user.withoutMfa());
        pendingTotp.remove(user.id());
        accounts.audit().record(new AuditLog.Actor(user.id(), user.email()), clientIp(ctx), "disable second factor");
        Logger.info("{} disabled their second factor", user.email());
        ctx.status(204);
    }

    /**
     * The signed-in user for a self-service write, or empty after answering.
     *
     * <p>These live under {@code /api/auth/*}, which the gate leaves alone — so both of the gate's
     * checks are made here by hand: a session must exist, and a write must carry
     * {@code X-Fordism-Request} so a cross-site form cannot ride the cookie to enable or strip a
     * second factor.
     */
    private java.util.Optional<User> selfService(Context ctx) {
        if (ctx.header(AuthGate.REQUEST_HEADER) == null) {
            Api.fail(ctx, 403, "a write must carry the " + AuthGate.REQUEST_HEADER + " header");
            return java.util.Optional.empty();
        }
        java.util.Optional<User> user = accounts.signedIn(ctx);
        if (user.isEmpty()) {
            Api.fail(ctx, 401, "not signed in");
        }
        return user;
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

    /**
     * Whether the second factor checks out: a valid TOTP code, or an unused recovery code — and a
     * spent recovery code is persisted here so it cannot be replayed.
     */
    private boolean secondFactorHolds(User user, Map<String, Object> body) {
        String code = Api.string(body.get("code")).trim();
        if (!code.isEmpty() && user.mfa().verifyTotp(code)) {
            return true;
        }
        String recovery = Api.string(body.get("recoveryCode")).trim();
        if (recovery.isEmpty()) {
            return false;
        }
        return user.mfa().redeemRecoveryCode(recovery)
                .map(remaining -> {
                    accounts.users().update(user.withMfa(remaining));
                    Logger.info("{} used a recovery code; {} left", user.email(), remaining.remainingRecoveryCodes());
                    return true;
                })
                .orElse(false);
    }

    /** The caller's IP for throttling — the socket peer, or the proxy header when one is trusted. */
    private String clientIp(Context ctx) {
        return accounts.configuration().clientIp(ctx.ip(), ctx.header("X-Forwarded-For"));
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
