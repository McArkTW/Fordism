package tw.mcark.tony.fordism.web;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.skill.SkillPluginStore;
import tw.mcark.tony.fordism.skill.SkillStore;
import tw.mcark.tony.fordism.skill.SkillView;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /api/skills[...] — skills library CRUD (namespaced names) + zip upload. */
public final class SkillController {
    private static final Gson GSON = new Gson();
    private final SkillStore skills;
    private final SkillPluginStore plugins;

    public SkillController(SkillStore skills, SkillPluginStore plugins) {
        this.skills = skills;
        this.plugins = plugins;
    }

    /**
     * GET /api/skills — every skill, each attributed to the plugin whose folder it sits in.
     *
     * <p>The join lives here rather than in {@link SkillStore} so the library never has to know
     * the registry exists: a store that called into the plugin store while the plugin store was
     * mid-sync would be two locks taken in two orders.
     */
    public void list(Context ctx) {
        Set<String> owners = plugins.names();
        List<SkillView> rows = new ArrayList<>();
        for (SkillView skill : skills.list()) {
            String namespace = namespaceOf(skill.name());
            rows.add(owners.contains(namespace) ? skill.ownedBy(namespace) : skill);
        }
        ctx.contentType("application/json").result(Json.write(rows));
    }

    /** The folder a skill sits in — {@code access/github} is owned by a plugin named {@code access}. */
    private static String namespaceOf(String name) {
        int cut = name.indexOf('/');
        return cut < 0 ? name : name.substring(0, cut);
    }

    /**
     * GET /api/skills-file?name=&lt;namespaced&gt;&amp;path=&lt;relative&gt; — one file inside a skill.
     *
     * <p>Its own path rather than {@code /api/skills/&lt;name&gt;/file}, because {@code <name>}
     * matches slashes: a namespaced name would swallow the {@code /file} segment whole. That is the
     * same reason {@code /api/skills-source} and {@code /api/skills-state} are shaped this way.
     */
    public void file(Context ctx) {
        try {
            String name = ctx.queryParam("name");
            String path = ctx.queryParam("path");
            if (name == null || path == null) {
                Api.fail(ctx, 400, "name and path are required");
                return;
            }
            Api.json(ctx, skills.readFile(name, path));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 500, "read failed");
        }
    }

    /**
     * POST /api/skills-delete {names:[…]} — delete a selection, reporting per name.
     *
     * <p>Answers 200 with {@code {deleted, failed}} even when some names failed: the page selected
     * twenty skills and needs to know which of them went. A single status code cannot say that, and
     * one DELETE per name would give the same answer in twenty round trips with no way to report
     * the half that died.
     */
    public void deleteMany(Context ctx) {
        try {
            List<String> names = Api.names(Api.body(ctx).get("names"));
            if (names.isEmpty()) {
                Api.fail(ctx, 400, "pick at least one skill");
                return;
            }
            Map<String, String> failures = skills.deleteAll(names);
            List<String> deleted = new ArrayList<>(names);
            deleted.removeAll(failures.keySet());
            List<Map<String, String>> failed = new ArrayList<>();
            for (Map.Entry<String, String> failure : failures.entrySet()) {
                failed.add(Map.of("name", failure.getKey(), "error", failure.getValue()));
            }
            Api.json(ctx, Map.of("deleted", deleted, "failed", failed));
        } catch (Exception e) {
            Api.fail(ctx, 400, "delete failed");
        }
    }

    public void get(Context ctx) {
        try {
            ctx.contentType("application/json").result(Json.write(skills.read(ctx.pathParam("name"))));
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 500, "read failed");
        }
    }

    public void save(Context ctx) {
        try {
            Map<String, Object> body = Api.body(ctx);
            String name = Api.string(body.get("name"));
            skills.write(name, Api.string(body.get("content")));
            Api.ok(ctx, "name", name);
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 400, "save failed");
        }
    }

    public void delete(Context ctx) {
        try {
            skills.delete(ctx.pathParam("name"));
            ctx.status(204);
        } catch (IllegalArgumentException e) {
            // A name the store refuses is the caller's mistake, not the server's.
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 500, "delete failed");
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
}
