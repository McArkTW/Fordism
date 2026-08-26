package com.hp.vcosmos.foundry.web;

import com.hp.vcosmos.foundry.workspace.AgentTemplate;
import com.hp.vcosmos.foundry.workspace.TemplateStore;
import io.javalin.http.Context;
import java.util.Map;

/** {@code /api/templates[...]} — agent-template CRUD, id-keyed (rename in place). */
public final class TemplateController {
    private final TemplateStore templates;

    public TemplateController(TemplateStore templates) {
        this.templates = templates;
    }

    public void list(Context ctx) {
        Api.json(ctx, templates.list());
    }

    public void get(Context ctx) {
        try {
            Api.json(ctx, templates.read(ctx.pathParam("id")));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 500, "read failed");
        }
    }

    public void create(Context ctx) {
        try {
            Api.ok(ctx, "id", templates.create(submitted(null, ctx)));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 400, "create failed");
        }
    }

    public void update(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            templates.update(id, submitted(id, ctx));
            Api.ok(ctx, "id", id);
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 400, "update failed");
        }
    }

    /** The template the request body describes. */
    private static AgentTemplate submitted(String id, Context ctx) {
        Map<String, Object> body = Api.body(ctx);
        return new AgentTemplate(id, Api.string(body.get("name")), Api.string(body.get("agentProfile")),
                Api.string(body.get("model")), Api.names(body.get("skills")),
                Api.names(body.get("credentials")), Api.string(body.get("instructions")));
    }

    public void delete(Context ctx) {
        try {
            templates.delete(ctx.pathParam("id"));
            ctx.status(204);
        } catch (Exception e) {
            Api.fail(ctx, 500, "delete failed");
        }
    }
}
