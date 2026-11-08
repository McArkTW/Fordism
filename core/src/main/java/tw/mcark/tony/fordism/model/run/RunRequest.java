package tw.mcark.tony.fordism.model.run;

import java.util.Map;

/**
 * What starting a run asks for: the workflow, its parameter values, an optional uploaded task.zip,
 * and optionally the run whose {@code artifact/} the new run should start from.
 *
 * <p>A record because the list grew past what a positional signature carries safely — two adjacent
 * nullable strings (a zip path and a run id) are exactly the pair a call site swaps unnoticed.
 */
public record RunRequest(String workflow, Map<String, String> parameters, String taskZipPath, String artifactFrom) {

    public RunRequest {
        parameters = parameters == null ? Map.of() : parameters;
        artifactFrom = artifactFrom == null || artifactFrom.isBlank() ? null : artifactFrom.trim();
    }

    public static RunRequest of(String workflow, Map<String, String> parameters) {
        return new RunRequest(workflow, parameters, null, null);
    }
}
