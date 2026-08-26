package com.hp.vcosmos.foundry.web;

import com.hp.vcosmos.foundry.agentprofile.AgentProfileStore;
import com.hp.vcosmos.foundry.config.FoundryConfiguration;
import com.hp.vcosmos.foundry.credential.CredentialStore;
import com.hp.vcosmos.foundry.model.run.WorkflowRun;
import com.hp.vcosmos.foundry.model.workflow.Parameter;
import com.hp.vcosmos.foundry.model.workflow.Step;
import com.hp.vcosmos.foundry.model.workflow.Workflow;
import com.hp.vcosmos.foundry.orchestrate.Engine;
import com.hp.vcosmos.foundry.workspace.AgentTemplate;
import com.hp.vcosmos.foundry.workspace.TemplateStore;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.hp.vcosmos.foundry.credential.Credential;
import java.util.Optional;

/** /api/workflows[...] — list, get, and run a workflow. */
public final class WorkflowController {
    private final Engine engine;
    private final FoundryConfiguration configuration;
    private final TemplateStore templates;
    private final AgentProfileStore profiles;
    private final CredentialStore credentials;

    public WorkflowController(Engine engine, FoundryConfiguration configuration, TemplateStore templates,
            AgentProfileStore profiles, CredentialStore credentials) {
        this.engine = engine;
        this.configuration = configuration;
        this.templates = templates;
        this.profiles = profiles;
        this.credentials = credentials;
    }

    /** Fast-fail: every step that NAMES a template must resolve, and that template must carry a real Agent Profile. */
    private String validateReferences(Workflow workflow) {
        for (Step step : workflow.steps()) {
            String problem = validateStepTemplate(step);
            if (problem != null) {
                return problem;
            }
        }
        return workflow.generator() != null ? validateStepTemplate(workflow.generator()) : null;
    }

    /** Null when the step's template is usable (or the step is template-less); otherwise a human-readable reason. */
    private String validateStepTemplate(Step step) {
        String templateName = step.template();
        if (templateName == null || templateName.isBlank()) {
            return null;   // a template-less step intentionally runs bare on the default gateway
        }
        AgentTemplate template = templates.getByName(templateName).orElse(null);
        if (template == null) {
            return "template \"" + templateName + "\" not found — create it on the Agent templates page";
        }
        String profile = template.agentProfile();
        if (profile == null || profile.isBlank()) {
            return "template \"" + templateName + "\" has no Agent Profile — create an Agent Profile and set it on the template before running this workflow";
        }
        if (profiles.getByName(profile).isEmpty()) {
            return "template \"" + templateName + "\" references Agent Profile \"" + profile + "\" which does not exist";
        }
        // A credential the template names but the store cannot supply would reach the agent as a
        // missing variable half an hour in, and it would stop and ask for something already granted.
        for (String key : template.credentials()) {
            Optional<Credential> credential = credentials.get(key);
            if (credential.isEmpty()) {
                return "template \"" + templateName + "\" needs credential \"" + key
                        + "\" which does not exist — add it on the Credentials page";
            }
            if (!credential.get().hasValue()) {
                return "credential \"" + key + "\" has no value — set one on the Credentials page";
            }
        }
        return null;
    }

    public void list(Context ctx) {
        List<Views.WorkflowSummary> out = new ArrayList<>();
        for (Workflow workflow : engine.allWorkflows()) {
            out.add(new Views.WorkflowSummary(workflow.name(), workflow.strategy().wireName(), workflow.steps().size(),
                    workflow.description(), workflow.tags(), templatesOf(workflow)));
        }
        Api.json(ctx, out);
    }

    public void get(Context ctx) {
        Workflow workflow = engine.workflow(ctx.pathParam("name")).orElse(null);
        if (workflow == null) {
            Api.fail(ctx, 404, "unknown workflow");
            return;
        }
        Api.json(ctx, parsed(workflow).withYaml(engine.workflowYaml(workflow.name())));
    }

    /**
     * Create or replace. A workflow is keyed by its name, so editing the name of an existing one
     * writes a second workflow and leaves the first behind — refused unless the caller passes
     * {@code ?saveAs=true} and means it.
     */
    public void save(Context ctx) {
        try {
            Workflow candidate = engine.parse(ctx.body());
            // This handler serves both POST /api/workflows (create) and PUT /api/workflows/{name}
            // (edit). pathParam throws on the create route, where there is no {name} to read — so
            // "New workflow → Save" failed before it saved anything.
            String editing = ctx.pathParamMap().getOrDefault("name", "");
            boolean saveAs = "true".equalsIgnoreCase(ctx.queryParam("saveAs"));
            if (!editing.isBlank() && !editing.equals(candidate.name()) && !saveAs) {
                ctx.status(409).contentType("application/json").result(Json.write(Map.of(
                        "error", "renaming would create a second workflow and leave \"" + editing
                                + "\" behind — save as a new workflow, or keep the name",
                        "was", editing,
                        "now", candidate.name())));
                return;
            }
            ctx.status(200).contentType("application/json").result(Json.write(parsed(engine.upsert(ctx.body()))));
        } catch (IllegalArgumentException e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"invalid YAML: " + escape(e.getMessage()) + "\"}");
        }
    }

    /** Parse without storing — what the editor calls to draw its outline and show its errors. */
    public void validate(Context ctx) {
        try {
            ctx.status(200).contentType("application/json").result(Json.write(parsed(engine.parse(ctx.body()))));
        } catch (IllegalArgumentException e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"invalid YAML: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Whether this workflow could start now, and why not. The same check {@link #run} makes,
     * exposed so the run page can say so before the button rather than as a 400 after it.
     */
    public void preflight(Context ctx) {
        Workflow workflow = engine.workflow(ctx.pathParam("name")).orElse(null);
        if (workflow == null) {
            Api.fail(ctx, 404, "unknown workflow");
            return;
        }
        String problem = validateReferences(workflow);
        Api.json(ctx, new Views.Preflight(problem == null, problem == null ? "" : problem));
    }

    /** The shape the engine parsed — the editor draws its outline from this, never from its own parse. */
    private Views.WorkflowDetail parsed(Workflow workflow) {
        List<Views.WorkflowStep> steps = new ArrayList<>();
        for (Step step : workflow.steps()) {
            steps.add(new Views.WorkflowStep(step.id(), nz(step.template()), step.dependsOn(),
                    nz(step.forEach()), nz(step.when()), step.includePreviousResult(),
                    step.config().timeoutSeconds()));
        }
        List<Views.WorkflowParameter> parameters = new ArrayList<>();
        for (Parameter parameter : workflow.parameters()) {
            parameters.add(new Views.WorkflowParameter(parameter.name(), parameter.labelOrName(),
                    parameter.type(), parameter.required(), parameter.defaultValue(), parameter.help()));
        }
        return new Views.WorkflowDetail(workflow.name(), workflow.description(), workflow.strategy().wireName(),
                workflow.tags(), workflow.maxIterations(), parameters, steps,
                workflow.generator() == null ? "" : workflow.generator().template(), null);
    }

    /** The editor renders an absent optional as an empty field, not the word "null". */
    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** Distinct templates a workflow names, so the list can be filtered by the template it runs. */
    private List<String> templatesOf(Workflow workflow) {
        List<String> out = new ArrayList<>();
        List<Step> all = new ArrayList<>(workflow.steps());
        if (workflow.generator() != null) {
            all.add(workflow.generator());
        }
        for (Step step : all) {
            if (step.template() != null && !step.template().isBlank() && !out.contains(step.template())) {
                out.add(step.template());
            }
        }
        return out;
    }

    public void delete(Context ctx) {
        try {
            engine.delete(ctx.pathParam("name"));
            ctx.status(204);
        } catch (Exception e) {
            ctx.status(500).contentType("application/json").result("{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    public void run(Context ctx) {
        String name = ctx.pathParam("name");
        Workflow workflow = engine.workflow(name).orElse(null);
        if (workflow == null) {
            Api.fail(ctx, 404, "unknown workflow");
            return;
        }
        String problem = validateReferences(workflow);
        if (problem != null) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"" + escape(problem) + "\"}");
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : ctx.queryParamMap().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                params.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        for (Map.Entry<String, List<String>> entry : ctx.formParamMap().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                params.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        try {
            String zipPath = null;
            UploadedFile upload = ctx.uploadedFile("taskZip");
            if (upload != null) {
                Path zipDir = Paths.get(configuration.workspacesDir, "_input", UUID.randomUUID().toString());
                Files.createDirectories(zipDir);
                Path zip = zipDir.resolve("task.zip");
                try (InputStream in = upload.content()) {
                    Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
                }
                zipPath = zip.toString();
            }
            WorkflowRun run = engine.createRun(name, params, zipPath);
            ctx.status(202).contentType("application/json")
                    .result(Json.write(new Views.RunStarted(run.id, name, run.state.toString())));
        } catch (IllegalArgumentException e) {
            ctx.status(400).contentType("application/json").result("{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            ctx.status(500).contentType("application/json").result("{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

}
