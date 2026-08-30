package tw.mcark.tony.fordism.auth;

/**
 * A signed-in browser: an opaque token, whose account it belongs to, and when it stops working.
 *
 * <p>Opaque rather than a self-contained token, because a server-side record is the only kind that
 * can be revoked — deleting an account has to end its sessions, not wait out their expiry.
 */
public record Session(String token, String userId, long expiresAt) {

    public boolean isLiveAt(long now) {
        return now < expiresAt;
    }
}
