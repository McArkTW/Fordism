package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.auth.Accounts;
import tw.mcark.tony.fordism.auth.Group;
import tw.mcark.tony.fordism.auth.GroupStore;
import tw.mcark.tony.fordism.auth.PasswordHash;
import tw.mcark.tony.fordism.auth.User;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tinylog.Logger;

/**
 * {@code /api/users[...]} — the accounts, from the admin side.
 *
 * <p>A password is write-only: it arrives in a body and leaves as a hash, and no response this
 * class produces has a field it could come back in. A blank password on an edit keeps the stored
 * one, the same rule the credential store uses, so changing a display name does not mean re-typing
 * somebody's password.
 */
public final class UserController {
    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    private final Accounts accounts;

    public UserController(Accounts accounts) {
        this.accounts = accounts;
    }

    public void list(Context ctx) {
        List<Views.UserSummary> out = new ArrayList<>();
        for (User user : accounts.users().all()) {
            out.add(AccountViews.summary(user));
        }
        Api.json(ctx, out);
    }

    public void create(Context ctx) {
        try {
            Map<String, Object> body = Api.body(ctx);
            String password = Api.string(body.get("password"));
            if (!password.isEmpty() && password.length() < MINIMUM_PASSWORD_LENGTH) {
                Api.fail(ctx, 400, "a password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters");
                return;
            }
            // No password is legitimate: an account that only ever signs in through a provider.
            User draft = new User(null, Api.string(body.get("email")), Api.string(body.get("displayName")),
                    password.isEmpty() ? "" : PasswordHash.of(password).encoded(), List.of());
            User created = accounts.users().create(draft);
            Logger.info("created user {}", created.email());
            Api.json(ctx, AccountViews.summary(created));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        }
    }

    public void update(Context ctx) {
        User existing = accounts.users().find(ctx.pathParam("id")).orElse(null);
        if (existing == null) {
            Api.fail(ctx, 404, "no user with id " + ctx.pathParam("id"));
            return;
        }
        try {
            Map<String, Object> body = Api.body(ctx);
            String email = Api.string(body.get("email"));
            String displayName = Api.string(body.get("displayName"));
            User edited = new User(existing.id(),
                    email.isBlank() ? existing.email() : email,
                    displayName.isBlank() ? existing.displayName() : displayName,
                    existing.passwordHash(), existing.identities());
            Api.json(ctx, AccountViews.summary(
                    accounts.users().update(withNewPassword(edited, Api.string(body.get("password"))))));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        }
    }

    /**
     * Delete an account, take it out of every group, and end its sessions.
     *
     * <p>Refused when it was the last member of the last group granting {@code *} — the same
     * lockout the group endpoints guard against, reached from the other direction.
     */
    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        User doomed = accounts.users().find(id).orElse(null);
        if (doomed == null) {
            Api.fail(ctx, 404, "no user with id " + id);
            return;
        }
        List<User> remainingUsers = new ArrayList<>(accounts.users().all());
        remainingUsers.removeIf(user -> user.id().equals(id));
        List<Group> remainingGroups = new ArrayList<>();
        for (Group group : accounts.groups().all()) {
            remainingGroups.add(group.withoutMember(id));
        }
        if (!GroupStore.fullAccessSurvives(remainingUsers, remainingGroups)) {
            Api.fail(ctx, 409, "deleting " + doomed.email()
                    + " would leave nobody in a group granting \"*\" — give somebody else full access first");
            return;
        }
        accounts.groups().replaceAll(remainingGroups);
        accounts.users().delete(id);
        accounts.sessions().invalidateUser(id);
        Logger.info("deleted user {}", doomed.email());
        ctx.status(204);
    }

    private static User withNewPassword(User user, String password) {
        if (password.isEmpty()) {
            return user;
        }
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "a password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters");
        }
        return user.withPasswordHash(PasswordHash.of(password));
    }
}
