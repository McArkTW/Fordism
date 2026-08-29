package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.auth.Accounts;
import tw.mcark.tony.fordism.auth.Group;
import tw.mcark.tony.fordism.auth.GroupStore;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tinylog.Logger;

/**
 * {@code /api/groups[...]} — membership and grant patterns.
 *
 * <p>Every edit and delete is checked against one invariant: somebody must still be in a group that
 * grants {@code *}. An instance whose last administrator was removed cannot be repaired from the
 * UI — the bootstrap does not reopen — so the only place to catch that mistake is before it is
 * written.
 */
public final class GroupController {

    private final Accounts accounts;

    public GroupController(Accounts accounts) {
        this.accounts = accounts;
    }

    public void list(Context ctx) {
        List<Views.GroupSummary> out = new ArrayList<>();
        for (Group group : accounts.groups().all()) {
            out.add(AccountViews.summary(group));
        }
        Api.json(ctx, out);
    }

    public void create(Context ctx) {
        try {
            Group created = accounts.groups().create(fromBody(ctx, null));
            Logger.info("created group {} granting {}", created.name(), created.grants());
            Api.json(ctx, AccountViews.summary(created));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        }
    }

    public void update(Context ctx) {
        String id = ctx.pathParam("id");
        Group existing = accounts.groups().find(id).orElse(null);
        if (existing == null) {
            Api.fail(ctx, 404, "no group with id " + id);
            return;
        }
        try {
            Group edited = fromBody(ctx, existing);
            List<Group> proposed = new ArrayList<>();
            for (Group group : accounts.groups().all()) {
                proposed.add(group.id().equals(id) ? edited : group);
            }
            if (!GroupStore.fullAccessSurvives(accounts.users().all(), proposed)) {
                Api.fail(ctx, 409, refusal("editing " + existing.name()));
                return;
            }
            Api.json(ctx, AccountViews.summary(accounts.groups().update(edited)));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        }
    }

    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        Group doomed = accounts.groups().find(id).orElse(null);
        if (doomed == null) {
            Api.fail(ctx, 404, "no group with id " + id);
            return;
        }
        List<Group> proposed = new ArrayList<>(accounts.groups().all());
        proposed.removeIf(group -> group.id().equals(id));
        if (!GroupStore.fullAccessSurvives(accounts.users().all(), proposed)) {
            Api.fail(ctx, 409, refusal("deleting " + doomed.name()));
            return;
        }
        accounts.groups().delete(id);
        Logger.info("deleted group {}", doomed.name());
        ctx.status(204);
    }

    /** The group the body describes; absent fields keep what {@code existing} already had. */
    private static Group fromBody(Context ctx, Group existing) {
        Map<String, Object> body = Api.body(ctx);
        String name = Api.string(body.get("name"));
        List<String> members = body.containsKey("members")
                ? Api.names(body.get("members"))
                : keptMembers(existing);
        List<String> grants = body.containsKey("grants") ? Api.names(body.get("grants")) : keptGrants(existing);
        return new Group(existing == null ? null : existing.id(),
                name.isBlank() && existing != null ? existing.name() : name, members, grants);
    }

    private static List<String> keptMembers(Group existing) {
        return existing == null ? List.of() : existing.memberUserIds();
    }

    private static List<String> keptGrants(Group existing) {
        return existing == null ? List.of() : existing.grants();
    }

    private static String refusal(String change) {
        return change + " would leave no group granting \"*\" to anybody — "
                + "give another group full access, or add somebody to one, first";
    }
}
