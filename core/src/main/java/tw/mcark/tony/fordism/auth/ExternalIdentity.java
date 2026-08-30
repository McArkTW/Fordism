package tw.mcark.tony.fordism.auth;

/**
 * Who a provider says the person at the end of a callback is: its stable subject id for them, and
 * the address it has verified for them.
 *
 * <p>Only ever built from a proof — a signature Fordism checked, or a call Fordism made with the
 * access token it just exchanged. Never from a query parameter.
 */
public record ExternalIdentity(AuthProviderId provider, String subject, String email, String displayName) {

    public LinkedIdentity link() {
        return new LinkedIdentity(provider, subject);
    }
}
