package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.agentprofile.AgentProfile;
import tw.mcark.tony.fordism.agentprofile.AgentProfileStore;
import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import io.javalin.http.Context;
import java.util.Map;

/** {@code /api/agent-profiles[...]} — CRUD, id-keyed (rename in place; keys write-only). */
public final class AgentProfileController {
    private final AgentProfileStore profiles;
    private final TemplateStore templates;

    public AgentProfileController(AgentProfileStore profiles, TemplateStore templates) {
        this.profiles = profiles;
        this.templates = templates;
    }

    public void list(Context ctx) {
        Api.json(ctx, profiles.list());
    }

    public void get(Context ctx) {
        try {
            Api.json(ctx, profiles.read(ctx.pathParam("id")));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 500, "read failed");
        }
    }

    public void create(Context ctx) {
        try {
            Api.ok(ctx, "id", profiles.create(submitted(null, ctx)));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 400, "create failed");
        }
    }

    public void update(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            AgentProfile before = profiles.get(id).orElse(null);
            AgentProfile submitted = submitted(id, ctx);
            String newName = submitted.name();
            profiles.update(id, submitted);
            if (before != null && before.name() != null && !before.name().equals(newName)) {
                templates.retargetProfile(before.name(), newName);   // keep template references valid
            }
            Api.ok(ctx, "id", id);
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 400, "update failed");
        }
    }

    /** The profile the request body describes. A blank apiKey means "keep the stored one". */
    private static AgentProfile submitted(String id, Context ctx) {
        Map<String, Object> body = Api.body(ctx);
        return new AgentProfile(id, Api.string(body.get("name")), Api.string(body.get("baseUrl")),
                Api.string(body.get("apiKey")), Api.string(body.get("model")),
                AgentTool.from(Api.string(body.get("tool"))));
    }

    public void delete(Context ctx) {
        try {
            profiles.delete(ctx.pathParam("id"));
            ctx.status(204);
        } catch (Exception e) {
            Api.fail(ctx, 500, "delete failed");
        }
    }
}
