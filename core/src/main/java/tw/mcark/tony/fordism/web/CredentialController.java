package tw.mcark.tony.fordism.web;

import tw.mcark.tony.fordism.credential.CredentialStore;
import tw.mcark.tony.fordism.credential.CredentialView;
import tw.mcark.tony.fordism.workspace.AgentTemplate;
import tw.mcark.tony.fordism.workspace.TemplateStore;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/credentials[...]} — the credential store.
 *
 * <p>No endpoint returns a value. Every response is keys, notes, a {@code hasValue} flag and the
 * templates that declare the key; the only reader of a value is the launcher, in-process.
 */
public final class CredentialController {
    private final CredentialStore credentials;
    private final TemplateStore templates;

    public CredentialController(CredentialStore credentials, TemplateStore templates) {
        this.credentials = credentials;
        this.templates = templates;
    }

    /** Each credential with the templates that declare it — computed, so it cannot drift. */
    public void list(Context ctx) {
        List<AgentTemplate> allTemplates = templates.all();
        List<CredentialView> out = new ArrayList<>();
        for (CredentialView credential : credentials.list()) {
            out.add(credential.usedBy(templatesUsing(allTemplates, credential.key())));
        }
        Api.json(ctx, out);
    }

    public void get(Context ctx) {
        String key = ctx.pathParam("key");
        CredentialView credential = credentials.read(key).orElse(null);
        if (credential == null) {
            Api.fail(ctx, 404, "no credential named " + key);
            return;
        }
        Api.json(ctx, credential.usedBy(templatesUsing(templates.all(), key)));
    }

    /** Create or update. A blank value keeps the stored one. */
    public void save(Context ctx) {
        try {
            String key = ctx.pathParam("key");
            if (!CredentialStore.isValidKey(key)) {
                Api.fail(ctx, 400, "not a valid environment-variable name: " + key);
                return;
            }
            Map<String, Object> body = Api.body(ctx);
            credentials.save(key, Api.string(body.get("value")), Api.string(body.get("note")));
            Api.ok(ctx, "key", key);
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 500, "save failed");
        }
    }

    public void delete(Context ctx) {
        try {
            credentials.delete(ctx.pathParam("key"));
            ctx.status(204);
        } catch (IllegalArgumentException e) {
            Api.fail(ctx, 400, e.getMessage());
        } catch (Exception e) {
            Api.fail(ctx, 500, "delete failed");
        }
    }

    /**
     * Which of these templates declare {@code key}. Takes the templates rather than reading them,
     * because the list endpoint answers this once per credential and each read walks every manifest
     * on disk — N credentials × M templates file reads for one page load.
     */
    private static List<String> templatesUsing(List<AgentTemplate> allTemplates, String key) {
        List<String> out = new ArrayList<>();
        for (AgentTemplate template : allTemplates) {
            if (template.credentials().contains(key)) {
                out.add(template.name());
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
