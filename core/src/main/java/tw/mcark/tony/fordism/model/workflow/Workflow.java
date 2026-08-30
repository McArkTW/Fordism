package tw.mcark.tony.fordism.model.workflow;

import java.util.List;

/** A parsed workflow definition (one YAML file). */
public record Workflow(String name, String description, Strategy strategy, List<String> tags,
                       List<Parameter> parameters, List<Step> steps, Step generator, int maxIterations) {
    public Workflow {
        tags = tags == null ? List.of() : List.copyOf(tags);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
