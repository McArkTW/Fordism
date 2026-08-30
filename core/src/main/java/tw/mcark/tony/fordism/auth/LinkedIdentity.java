package tw.mcark.tony.fordism.auth;

/**
 * An external account linked to a Fordism user: the provider, and that provider's stable subject
 * identifier for the person.
 *
 * <p>The subject, not the email — an operator who renames their GitHub account or changes the
 * address on their Google account is still the same person, and matching on email alone would
 * hand their Fordism account to whoever inherits the old address.
 */
public record LinkedIdentity(AuthProviderId provider, String subject) {

    public boolean sameAs(LinkedIdentity other) {
        return other != null && provider == other.provider() && subject != null
                && subject.equals(other.subject());
    }
}
