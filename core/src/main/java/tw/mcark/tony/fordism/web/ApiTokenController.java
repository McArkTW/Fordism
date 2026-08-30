package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.auth.Accounts;
import tw.mcark.tony.fordism.auth.ApiToken;
import tw.mcark.tony.fordism.auth.ApiTokenStore;
import tw.mcark.tony.fordism.auth.AuthGate;
import tw.mcark.tony.fordism.auth.Permission;
import tw.mcark.tony.fordism.auth.PermissionMatcher;
import tw.mcark.tony.fordism.auth.User;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tinylog.Logger;

/**
 * {@code /api/api-tokens[...]} — the tokens that let a script, a CI job or a cron call this API
 * without a browser.
 *
 * <p>Every route here is scoped to the caller's OWN tokens. There is no endpoint that lists or
 * revokes somebody else's: an administrator who needs a person's tokens gone deletes or locks the
 * account, which revokes them along with their sessions.
 *
 * <p>The value is returned exactly once, from {@link #create}. Nothing else can produce it — the
 * store keeps only a hash — so a caller that loses it revokes the token and mints another.
 */
public final class ApiTokenController {

    /** A year. Long enough for a CI job nobody wants to re-plumb, short enough to be an expiry. */
    private static final long MAXIMUM_LIFETIME_DAYS = 365;

    private final Accounts accounts;

    public ApiTokenController(Accounts accounts) {
        this.accounts = accounts;
    }

    public void list(Context ctx) {
        List<Views.ApiTokenSummary> out = new ArrayList<>();
        for (ApiToken token : accounts.apiTokens().forUser(owner(ctx).id())) {
            out.add(summary(token));
        }
        Api.json(ctx, out);
    }

    public void create(Context ctx) {
        User user = owner(ctx);
        Map<String, Object> body = Api.body(ctx);
        List<String> grants = Api.names(body.get("grants"));
        try {
            requireKnownGrants(grants);
            ApiTokenStore.Minted minted = accounts.apiTokens().mint(new ApiToken(null, user.id(),
                    Api.string(body.get("name")), null, grants, 0L,
                    expiryFrom(body.get("expiresInDays")), 0L));
            Logger.info("{} minted API token {} granting {}", user.email(), minted.token().name(),
                    minted.token().grants());
            ctx.status(201);
            Api.json(ctx, new Views.MintedApiToken(summary(minted.token()), minted.value()));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        }
    }

    public void delete(Context ctx) {
        User user = owner(ctx);
        String id = ctx.pathParam("id");
        if (!accounts.apiTokens().revoke(id, user.id())) {
            // The same answer for "no such token" and "not yours": otherwise this endpoint tells
            // any signed-in account which token ids exist on the instance.
            Api.fail(ctx, 404, "no API token of yours with id " + id);
            return;
        }
        Logger.info("{} revoked API token {}", user.email(), id);
        ctx.status(204);
    }

    /**
     * Every grant must name a permission that exists, or a subtree that contains one.
     *
     * <p>A misspelled grant is not harmless here. It produces a token that silently does nothing —
     * every call refused for a reason that reads like a permissions problem — and the person who
     * typed it finds out from a broken CI job rather than from this endpoint.
     */
    private static void requireKnownGrants(List<String> grants) {
        for (String grant : grants) {
            if (PermissionMatcher.EVERYTHING.equals(grant) || coversSomething(grant)) {
                continue;
            }
            throw new IllegalArgumentException("grant \"" + grant + "\" matches no permission — "
                    + "one of " + known() + ", a trailing .* subtree of one, or * for everything");
        }
    }

    private static boolean coversSomething(String grant) {
        for (Permission permission : Permission.values()) {
            if (PermissionMatcher.matches(grant, permission.id())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> known() {
        List<String> ids = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            ids.add(permission.id());
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /** Absent or zero means it never expires; anything past a year is refused rather than clamped. */
    private static long expiryFrom(Object value) {
        String text = Api.string(value).trim();
        if (text.isEmpty()) {
            return ApiToken.NEVER_EXPIRES;
        }
        long days;
        try {
            days = (long) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expiresInDays must be a number of days");
        }
        if (days <= 0) {
            return ApiToken.NEVER_EXPIRES;
        }
        if (days > MAXIMUM_LIFETIME_DAYS) {
            throw new IllegalArgumentException("expiresInDays must be at most " + MAXIMUM_LIFETIME_DAYS);
        }
        return System.currentTimeMillis() + Duration.ofDays(days).toMillis();
    }

    private static Views.ApiTokenSummary summary(ApiToken token) {
        return new Views.ApiTokenSummary(token.id(), token.name(), token.grants(), token.createdAt(),
                token.expiresAt(), token.lastUsedAt());
    }

    /** The gate admitted somebody, so this is always present behind it. */
    private static User owner(Context ctx) {
        return AuthGate.currentUser(ctx).orElseThrow(() ->
                new IllegalStateException("the gate let an unauthenticated request reach /api/api-tokens"));
    }
}
