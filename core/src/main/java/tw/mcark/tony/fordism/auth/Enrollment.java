package tw.mcark.tony.fordism.auth;

import java.util.Locale;
import java.util.Optional;
import org.tinylog.Logger;

/**
 * Which Fordism account a proven external identity signs in as — the whole admission policy for
 * OAuth, in one place, so adding a provider cannot widen it.
 *
 * <p>Three steps, in order:
 * <ol>
 *   <li>a user already linked to this provider subject — the normal repeat sign-in;
 *   <li>a user with the same email — first sign-in for someone who already has an account, whose
 *       identity is linked on the spot so a later address change does not orphan them;
 *   <li>nobody — enrol a brand-new account <em>only</em> if the address is on the allowlist, and
 *       with no groups, so an enrolled stranger can see that they are logged in and nothing else.
 * </ol>
 *
 * <p>With both allowlists empty nothing auto-enrols, which is the right default: an install that
 * has not said who may join should not decide that anyone with a Google account may.
 */
public final class Enrollment {

    private final AuthConfiguration configuration;
    private final UserStore users;

    public Enrollment(AuthConfiguration configuration, UserStore users) {
        this.configuration = configuration;
        this.users = users;
    }

    /** The account to sign in, or empty when this instance does not admit that person. */
    public Optional<User> resolve(ExternalIdentity identity) {
        LinkedIdentity link = identity.link();
        Optional<User> linked = users.findByIdentity(link);
        if (linked.isPresent()) {
            return linked;
        }
        Optional<User> byEmail = users.findByEmail(identity.email());
        if (byEmail.isPresent()) {
            return Optional.of(users.update(byEmail.get().linkedTo(link)));
        }
        if (!admits(identity.email())) {
            Logger.warn("refused {} sign-in for {} — not on FORDISM_AUTH_ALLOWED_EMAILS or _DOMAINS",
                    identity.provider().id(), identity.email());
            return Optional.empty();
        }
        User enrolled = users.create(User.fromProvider(identity));
        Logger.info("auto-enrolled {} from {} with no groups", enrolled.email(), identity.provider().id());
        return Optional.of(enrolled);
    }

    /** Whether an unknown address may create an account by signing in. */
    public boolean admits(String email) {
        String address = User.normalizedEmail(email);
        if (address.isEmpty()) {
            return false;
        }
        if (configuration.allowedEmails().contains(address)) {
            return true;
        }
        int at = address.indexOf('@');
        return at >= 0 && configuration.allowedDomains()
                .contains(address.substring(at + 1).toLowerCase(Locale.ROOT));
    }
}
