package tw.mcark.tony.fordism.auth;

import java.util.List;

/**
 * A long-lived credential for calling the API without a browser: a script, a CI job, a cron.
 *
 * <p>{@code tokenHash} is a SHA-256 of the value, never the value. The value is 256 bits from
 * {@link java.security.SecureRandom} and is shown exactly once, when it is minted — a store that
 * could hand it back is a store that leaks every token the moment one read endpoint is misjudged.
 *
 * <p>Plain SHA-256 rather than the password KDF on purpose. {@link PasswordHash} is deliberately
 * slow, which is right for eight characters a human chose and wrong for 256 random bits checked on
 * every single request: there is nothing to brute-force, and a slow hash on the hot path is a
 * denial of service anyone can trigger by sending nonsense.
 *
 * <p>{@code grants} are patterns in the same language a group uses, and they only ever NARROW. What
 * a token may do is the intersection of these and the grants its owner holds through their groups,
 * so a token can never outlive its owner's permissions or exceed them — see {@link Caller}.
 */
public record ApiToken(String id, String userId, String name, String tokenHash, List<String> grants,
                       long createdAt, long expiresAt, long lastUsedAt) {

    /** A token with no end date. Written down as 0 rather than absent so the field always reads. */
    public static final long NEVER_EXPIRES = 0L;

    public ApiToken {
        name = name == null || name.isBlank() ? "unnamed token" : name.trim();
        grants = grants == null || grants.isEmpty()
                ? List.of(PermissionMatcher.EVERYTHING)
                : List.copyOf(grants);
    }

    /** Whether this token is still good. An expired row is refused and swept on the next write. */
    public boolean isLiveAt(long now) {
        return expiresAt == NEVER_EXPIRES || now < expiresAt;
    }

    /** Whether this token's own grants cover the permission — half of the intersection. */
    public boolean allows(Permission required) {
        return PermissionMatcher.anyMatches(grants, required);
    }

    public ApiToken usedAt(long now) {
        return new ApiToken(id, userId, name, tokenHash, grants, createdAt, expiresAt, now);
    }
}
