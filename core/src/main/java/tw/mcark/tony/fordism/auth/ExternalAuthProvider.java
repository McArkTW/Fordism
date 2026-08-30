package tw.mcark.tony.fordism.auth;

import java.util.Optional;

/**
 * One OAuth sign-in provider: where to send the browser, and what the callback proves.
 *
 * <p>Two methods because that is the whole protocol from Fordism's side. Everything else — which
 * account the identity maps to, whether an unknown one may enrol — belongs to {@link Enrollment},
 * so a new provider cannot accidentally bring its own admission policy.
 */
public interface ExternalAuthProvider {

    /** Where to redirect the browser to begin this attempt. */
    String authorizationUrl(LoginAttempt attempt);

    /** The identity the callback proves, or empty when it proves nothing. */
    Optional<ExternalIdentity> identify(LoginCallback callback);
}
