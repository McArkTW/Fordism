package tw.mcark.tony.fordism.model.workflow;

import java.util.List;
import java.util.OptionalInt;

/** A parsed workflow definition (one YAML file). */
public record Workflow(String name, String description, Strategy strategy, List<String> tags,
                       List<Parameter> parameters, List<Step> steps, Step generator, int maxIterations) {
    public Workflow {
        tags = tags == null ? List.of() : List.copyOf(tags);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /**
     * The step whose agent the step at {@code index} continues, or empty when it starts its own.
     *
     * <p>One rule per strategy, in one place, so the loader that validates it and the engine that
     * acts on it cannot disagree: a {@code graph} step continues its one dependency, every other
     * strategy that allows it continues the step before it in list order. The loader has already
     * refused every shape where neither names exactly one step.
     */
    public OptionalInt continuedStep(int index) {
        if (index < 0 || index >= steps.size() || !steps.get(index).continues()) {
            return OptionalInt.empty();
        }
        Step step = steps.get(index);
        if (strategy == Strategy.GRAPH) {
            if (step.dependsOn().size() != 1) {
                return OptionalInt.empty();
            }
            for (int other = 0; other < steps.size(); other++) {
                if (steps.get(other).id().equals(step.dependsOn().get(0))) {
                    return OptionalInt.of(other);
                }
            }
            return OptionalInt.empty();
        }
        return index == 0 ? OptionalInt.empty() : OptionalInt.of(index - 1);
    }
}
