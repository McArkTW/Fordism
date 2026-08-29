package tw.mcark.tony.fordism.web;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.skill.SkillStore;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * POST /api/skills/upload?name=&lt;namespaced&gt; — multipart: one {@code files} part per file in
     * the picked folder, plus a {@code paths} JSON array of their paths relative to it.
     *
     * <p>The browser knows each file's folder-relative path ({@code webkitRelativePath}); a
     * multipart part carries only the base name, so the paths ride alongside in the same order.
     */
    public void upload(Context ctx) {
        try {
            String name = ctx.queryParam("name");
            List<UploadedFile> uploaded = ctx.uploadedFiles("files");
            List<String> paths = Api.names(GSON.fromJson(ctx.formParam("paths"), List.class));
            if (uploaded.isEmpty()) {
                Api.fail(ctx, 400, "pick a folder to upload");
                return;
            }
            List<InputStream> contents = new ArrayList<>();
            try {
                for (UploadedFile file : uploaded) {
                    contents.add(file.content());
                }
                skills.writeFolder(name, paths.isEmpty() ? filenames(uploaded) : paths, contents);
            } finally {
                for (InputStream in : contents) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // The upload is already read; a close that fails cannot unwrite it.
                    }
                }
            }
            Api.ok(ctx, "name", name);
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 400, "upload failed");
        }
    }

    /** A flat drop of files, with no folder structure to preserve. */
    private static List<String> filenames(List<UploadedFile> uploaded) {
        List<String> out = new ArrayList<>();
        for (UploadedFile file : uploaded) {
            out.add(file.filename());
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
