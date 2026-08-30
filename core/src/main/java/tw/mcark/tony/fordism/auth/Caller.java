package tw.mcark.tony.fordism.auth;

import java.util.Optional;

/**
 * Who is making this request, and by what means: an account, and the API token it presented, if it
 * presented one.
 *
 * <p>The distinction matters twice. What a token-borne request may do is the INTERSECTION of the
 * token's grants and the account's — so a token is never a way to gain a permission, only ever a
 * way to hold fewer. And a token is not a browser, which is why it does not need the CSRF header a
 * cookie does, and why it is refused on the routes that mint tokens: a leaked token that could mint
 * another would be a leak with no end.
 */
public record Caller(User user, ApiToken token) {

    /** A person in a browser, holding a session cookie. */
    public static Caller session(User user) {
        return new Caller(user, null);
    }

    /** A script, holding an API token. */
    public static Caller bearing(User user, ApiToken token) {
        return new Caller(user, token);
    }

    public Optional<ApiToken> apiToken() {
        return Optional.ofNullable(token);
    }

    public boolean isToken() {
        return token != null;
    }

    /** Whether this caller may do the thing the route asked for. */
    public boolean allows(GroupStore groups, Permission required) {
        if (!groups.allows(user.id(), required)) {
            return false;
        }
        return token == null || token.allows(required);
    }
}
