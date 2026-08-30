package tw.mcark.tony.fordism.auth;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API tokens, as one JSON array at {@code <stateDir>/api-tokens.json}.
 *
 * <p>On disk beside sessions, and for the same reason: a server-side record is the only kind that
 * can be revoked. Deleting an account, or a token, has to stop it working now — not wait out an
 * expiry that a self-contained token would carry inside itself.
 *
 * <p>Only the hash is stored. {@link #mint} is the one moment the value exists, and it is handed
 * straight back to the caller that asked for it; nothing here can produce it again.
 */
public final class ApiTokenStore {
    private static final Type TYPE = new TypeToken<List<ApiToken>>() {}.getType();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final long USE_RECORDING_FLOOR_MILLIS = 60_000;

    /**
     * The prefix every token value carries. It is not a secret and it is not checked — it is there
     * so a token pasted into a file, a log or a commit is recognisable as one, by a person and by
     * the secret scanner in CI.
     */
    public static final String PREFIX = "fordism_pat_";

    private final JsonRecordFile<ApiToken> file;

    public ApiTokenStore(Path stateDir) {
        this.file = new JsonRecordFile<>(stateDir.resolve("api-tokens.json"), TYPE);
    }

    /**
     * Create a token for this account and persist its hash.
     *
     * <p>The returned {@link Minted} carries the only copy of the value there will ever be. A
     * caller that does not hand it to the person now has destroyed it.
     */
    public synchronized Minted mint(ApiToken draft) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String value = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        ApiToken token = new ApiToken(UUID.randomUUID().toString(), draft.userId(), draft.name(),
                hash(value), draft.grants(), System.currentTimeMillis(), draft.expiresAt(),
                ApiToken.NEVER_EXPIRES);
        List<ApiToken> live = live();
        live.add(token);
        file.write(live);
        return new Minted(token, value);
    }

    /**
     * The live token this presented value names, or empty.
     *
     * <p>Compared with {@link MessageDigest#isEqual}, which does not return early on the first
     * differing byte. Over a stored SHA-256 that is close to theatre, but it costs nothing and the
     * alternative is a comparison whose timing depends on a secret.
     */
    public synchronized Optional<ApiToken> find(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        byte[] wanted = hash(presented).getBytes(StandardCharsets.US_ASCII);
        long now = System.currentTimeMillis();
        for (ApiToken token : file.read()) {
            if (token.isLiveAt(now)
                    && MessageDigest.isEqual(wanted, token.tokenHash().getBytes(StandardCharsets.US_ASCII))) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    /** The tokens an account holds, newest first. Values are not among them. */
    public synchronized List<ApiToken> forUser(String userId) {
        List<ApiToken> mine = new ArrayList<>();
        for (ApiToken token : live()) {
            if (token.userId().equals(userId)) {
                mine.add(token);
            }
        }
        mine.sort((left, right) -> Long.compare(right.createdAt(), left.createdAt()));
        return mine;
    }

    /**
     * Revoke one token belonging to this account.
     *
     * <p>Scoped to the owner rather than looked up by id alone: without that, anyone who could
     * reach the endpoint could delete anyone's token by guessing a uuid.
     */
    public synchronized boolean revoke(String tokenId, String userId) {
        List<ApiToken> kept = live();
        boolean removed = kept.removeIf(token -> token.id().equals(tokenId) && token.userId().equals(userId));
        if (removed) {
            file.write(kept);
        }
        return removed;
    }

    /** Revoke every token an account holds — what deleting that account does, as with its sessions. */
    public synchronized void revokeUser(String userId) {
        List<ApiToken> kept = live();
        if (kept.removeIf(token -> token.userId().equals(userId))) {
            file.write(kept);
        }
    }

    /**
     * Record that a token was just used, for the "last used" column that makes a stale one prunable.
     *
     * <p>At most once a minute per token. This is called on every authenticated request, and the
     * file is read and written whole — a CI job polling a run would otherwise rewrite the token
     * file several times a second to move a timestamp nobody is watching that closely.
     */
    public synchronized void touch(ApiToken used) {
        long now = System.currentTimeMillis();
        if (now - used.lastUsedAt() < USE_RECORDING_FLOOR_MILLIS) {
            return;
        }
        List<ApiToken> kept = live();
        for (int index = 0; index < kept.size(); index++) {
            if (kept.get(index).id().equals(used.id())) {
                kept.set(index, kept.get(index).usedAt(now));
                file.write(kept);
                return;
            }
        }
    }

    private List<ApiToken> live() {
        long now = System.currentTimeMillis();
        List<ApiToken> kept = new ArrayList<>();
        for (ApiToken token : file.read()) {
            if (token.isLiveAt(now)) {
                kept.add(token);
            }
        }
        return kept;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no SHA-256 — API tokens cannot be stored", e);
        }
    }

    /** A token and the one copy of its value that will ever exist. */
    public record Minted(ApiToken token, String value) {}
}
