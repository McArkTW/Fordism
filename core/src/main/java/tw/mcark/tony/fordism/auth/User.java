package tw.mcark.tony.fordism.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One person who can sign in. Identity is the id (so changing an address is a field edit, never a
 * new account); the lowercased email is the unique login handle.
 *
 * <p>{@code passwordHash} is blank for an account that only signs in through a provider, and its
 * contents never leave the server — every browser-facing shape is built in {@code Views}, which has
 * no field for it.
 */
public record User(String id, String email, String displayName, String passwordHash,
                   List<LinkedIdentity> identities) {

    public User {
        // Gson hands back nulls for absent JSON fields; normalising here means no reader of a
        // loaded user has to null-check what the file happened to omit.
        email = normalizedEmail(email);
        displayName = displayName == null || displayName.isBlank() ? suggestedName(email) : displayName.trim();
        passwordHash = passwordHash == null ? "" : passwordHash;
        identities = identities == null ? List.of() : List.copyOf(identities);
    }

    /** A new local account, before the store assigns it an id. */
    public static User withPassword(String email, String displayName, PasswordHash password) {
        return new User(null, email, displayName, password.encoded(), List.of());
    }

    /** A new account that signs in through a provider and has no password of its own. */
    public static User fromProvider(ExternalIdentity identity) {
        return new User(null, identity.email(), identity.displayName(), "", List.of(identity.link()));
    }

    public boolean hasPassword() {
        return !passwordHash.isBlank();
    }

    /** The stored hash, or empty for a provider-only account — which can never match a password. */
    public Optional<PasswordHash> password() {
        return hasPassword() ? Optional.of(new PasswordHash(passwordHash)) : Optional.empty();
    }

    public boolean isLinkedTo(LinkedIdentity identity) {
        for (LinkedIdentity linked : identities) {
            if (linked.sameAs(identity)) {
                return true;
            }
        }
        return false;
    }

    public User withId(String assigned) {
        return new User(assigned, email, displayName, passwordHash, identities);
    }

    public User withPasswordHash(PasswordHash password) {
        return new User(id, email, displayName, password.encoded(), identities);
    }

    /** The same account with one more external login attached. */
    public User linkedTo(LinkedIdentity identity) {
        if (isLinkedTo(identity)) {
            return this;
        }
        List<LinkedIdentity> linked = new ArrayList<>(identities);
        linked.add(identity);
        return new User(id, email, displayName, passwordHash, linked);
    }

    /** Emails are compared lowercased everywhere, so they are stored that way once, here. */
    public static String normalizedEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String suggestedName(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
