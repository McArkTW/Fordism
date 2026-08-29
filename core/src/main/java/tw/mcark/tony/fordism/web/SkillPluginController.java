package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.skill.SkillPluginStore;
import io.javalin.http.Context;
import java.util.Map;

/** /api/skill-plugins[...] — the skills repos the library mirrors: list, add, sync, remove. */
public final class SkillPluginController {
    private final SkillPluginStore plugins;

    public SkillPluginController(SkillPluginStore plugins) {
        this.plugins = plugins;
    }

    public void list(Context ctx) {
        Api.json(ctx, plugins.list());
    }

    /** POST /api/skill-plugins {url, ref} — register and pull it in. */
    public void add(Context ctx) {
        try {
            Map<String, Object> body = Api.body(ctx);
            Api.json(ctx, plugins.add(Api.string(body.get("url")), Api.string(body.get("ref"))));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 400, "could not add the plugin");
        }
    }

    /** POST /api/skill-plugins/{id}/sync — re-pull, replacing the plugin's folder. */
    public void sync(Context ctx) {
        try {
            Api.json(ctx, plugins.sync(ctx.pathParam("id")));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 404, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 400, "sync failed");
        }
    }

    /** DELETE /api/skill-plugins/{id} — forget it and delete the skills it installed. */
    public void remove(Context ctx) {
        try {
            plugins.remove(ctx.pathParam("id"));
            ctx.status(204);
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 404, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 500, "could not remove the plugin");
        }
    }
}
