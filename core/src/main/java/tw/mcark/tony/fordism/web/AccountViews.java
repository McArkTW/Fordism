package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.auth.AuthProviderId;
import tw.mcark.tony.fordism.auth.Group;
import tw.mcark.tony.fordism.auth.GroupStore;
import tw.mcark.tony.fordism.auth.LinkedIdentity;
import tw.mcark.tony.fordism.auth.User;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an account into the shapes the browser sees.
 *
 * <p>One place, shared by the auth, user and group endpoints, because "never serialize a password
 * hash" is a rule that only holds if there is a single conversion to check.
 */
public final class AccountViews {

    private AccountViews() {}

    /** The signed-in user: their groups by name, and the effective grants those groups add up to. */
    public static Views.Me me(User user, GroupStore groups) {
        List<String> grants = new ArrayList<>(groups.grantsFor(user.id()));
        grants.sort(String::compareTo);
        return new Views.Me(user.id(), user.email(), user.displayName(),
                groups.groupNamesFor(user.id()), grants, user.mfaEnabled());
    }

    /**
     * A row on the Users page: the ways in, never the secret behind one. An account with an empty
     * {@code identities} is the one worth spotting — it exists and nobody can sign in as it.
     */
    public static Views.UserSummary summary(User user) {
        List<Views.Identity> identities = new ArrayList<>();
        if (user.hasPassword()) {
            identities.add(new Views.Identity(AuthProviderId.LOCAL.id(), user.email()));
        }
        for (LinkedIdentity linked : user.identities()) {
            identities.add(new Views.Identity(linked.provider().id(), linked.subject()));
        }
        return new Views.UserSummary(user.id(), user.email(), user.displayName(), identities,
                user.mfaEnabled());
    }

    public static Views.GroupSummary summary(Group group) {
        return new Views.GroupSummary(group.id(), group.name(), group.memberUserIds(), group.grants());
    }
}
