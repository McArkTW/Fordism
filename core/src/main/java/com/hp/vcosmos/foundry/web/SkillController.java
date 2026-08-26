package com.hp.vcosmos.foundry.web;

import com.google.gson.Gson;
import com.hp.vcosmos.foundry.skill.SkillStore;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import java.io.InputStream;
import java.util.Map;

/** /api/skills[...] — skills library CRUD (namespaced names) + zip upload. */
public final class SkillController {
    private static final Gson GSON = new Gson();
    private final SkillStore skills;

    public SkillController(SkillStore skills) {
        this.skills = skills;
    }

    public void list(Context ctx) {
        ctx.contentType("application/json").result(Json.write(skills.list()));
    }

    public void get(Context ctx) {
        try {
            ctx.contentType("application/json").result(Json.write(skills.read(ctx.pathParam("name"))));
        } catch (IllegalArgumentException e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            ctx.status(500).contentType("application/json").result("{\"error\":\"read failed\"}");
        }
    }

    public void save(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = GSON.fromJson(ctx.body(), Map.class);
            String name = str(body.get("name"));
            skills.write(name, str(body.get("content")));
            ctx.status(200).contentType("application/json").result("{\"name\":\"" + name + "\"}");
        } catch (IllegalArgumentException e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"save failed\"}");
        }
    }

    public void delete(Context ctx) {
        try {
            skills.delete(ctx.pathParam("name"));
            ctx.status(204);
        } catch (Exception e) {
            ctx.status(500).contentType("application/json").result("{\"error\":\"delete failed\"}");
        }
    }

    /** GET /api/skills-source — the mirror's repo + pinned tag ({repo, tag, syncedAt}). */
    public void source(Context ctx) {
        ctx.contentType("application/json").result(Json.write(skills.source()));
    }

    /** POST /api/skills-state {name, enabled} — include or exclude a skill in future runs. */
    public void setEnabled(Context ctx) {
        try {
            Map<String, Object> body = Api.body(ctx);
            String name = Api.string(body.get("name"));
            Object enabled = body.get("enabled");
            // Absent reads as enabled — the page only sends the field when it is turning one off.
            if (enabled == null || Boolean.parseBoolean(enabled.toString())) {
                skills.enable(name);
            } else {
                skills.disable(name);
            }
            Api.ok(ctx, "name", name);
        } catch (Exception e) {
            Api.fail(ctx, 400, "state update failed");
        }
    }

    /** POST /api/skills/upload?name=<namespaced> with multipart skillZip. */
    public void upload(Context ctx) {
        try {
            String name = ctx.queryParam("name");
            UploadedFile uploaded = ctx.uploadedFile("skillZip");
            if (uploaded == null) {
                ctx.status(400).contentType("application/json").result("{\"error\":\"skillZip required\"}");
                return;
            }
            try (InputStream in = uploaded.content()) {
                skills.upload(name, in);
            }
            ctx.status(200).contentType("application/json").result("{\"name\":\"" + name + "\"}");
        } catch (IllegalArgumentException e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"upload failed\"}");
        }
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
