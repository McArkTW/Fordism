package tw.mcark.tony.fordism.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.workflow.OnFail;
import tw.mcark.tony.fordism.model.workflow.ReworkMode;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import org.junit.jupiter.api.Test;

class WorkflowLoaderTest {
    private final WorkflowLoader loader = new WorkflowLoader();

    private static String graph(String dependency) {
        return """
                name: g
                strategy: graph
                steps:
                  - id: research
                    template: generic
                    task: research it
                  - id: synthesize
                    template: generic
                    task: weigh it up
                    dependsOn: [%s]
                """.formatted(dependency);
    }

    @Test
    void a_dependsOn_typo_is_refused_at_parse_time() {
        // Left to the orchestrator this is invisible: the step is never seeded, the run never
        // completes, and there is no task to reap — it stays ACTIVE forever.
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> loader.parse(graph("resarch")));
        assertTrue(thrown.getMessage().contains("resarch"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("does not exist"), thrown.getMessage());
    }

    @Test
    void a_dependsOn_that_resolves_is_accepted() {
        Workflow workflow = loader.parse(graph("research"));
        assertEquals(2, workflow.steps().size());
        assertEquals("research", workflow.steps().get(1).dependsOn().get(0));
    }

    @Test
    void an_onFail_retry_must_name_a_step() {
        assertThrows(IllegalArgumentException.class, () -> loader.parse("""
                name: r
                strategy: rework
                steps:
                  - id: work
                    template: generic
                    task: do it
                  - id: gate
                    template: generic
                    task: judge it
                    onFail:
                      retry: wrok
                      maxAttempts: 3
                """));
    }

    @Test
    void a_when_predicate_must_parse_and_name_a_step() {
        assertThrows(IllegalArgumentException.class, () -> loader.parse("""
                name: c
                strategy: conditional
                steps:
                  - id: classify
                    template: generic
                    task: yes or no
                  - id: review
                    template: generic
                    when: clasify.result == 'yes'
                    task: review it
                """));
        assertThrows(IllegalArgumentException.class, () -> loader.parse("""
                name: c
                strategy: conditional
                steps:
                  - id: classify
                    template: generic
                    task: yes or no
                  - id: review
                    template: generic
                    when: classify is yes
                    task: review it
                """));
    }

    @Test
    void an_unknown_key_is_refused_rather_than_ignored() {
        // dependOn for dependsOn produced a graph step with no dependencies: every step fired at
        // once, no error, and the first sign of it was the run.
        assertThrows(IllegalArgumentException.class, () -> loader.parse("""
                name: g
                strategy: graph
                steps:
                  - id: a
                    template: generic
                    task: do it
                    dependOn: [b]
                """));
    }

    private static String mapReduce(int stepCount) {
        StringBuilder yaml = new StringBuilder("""
                name: mr
                strategy: map-reduce
                parameters: [items]
                steps:
                """);
        for (int index = 0; index < stepCount; index++) {
            yaml.append("  - id: step").append(index).append("\n")
                    .append("    template: generic\n")
                    .append("    task: do part ").append(index).append("\n");
        }
        return yaml.toString();
    }

    @Test
    void a_map_reduce_workflow_must_have_exactly_two_steps() {
        // MapReduceOrchestrator reads only steps 0 and 1 — a third step would be silently ignored.
        IllegalArgumentException oneStep =
                assertThrows(IllegalArgumentException.class, () -> loader.parse(mapReduce(1)));
        assertTrue(oneStep.getMessage().contains("mr"), oneStep.getMessage());
        assertTrue(oneStep.getMessage().contains("1"), oneStep.getMessage());
        IllegalArgumentException threeSteps =
                assertThrows(IllegalArgumentException.class, () -> loader.parse(mapReduce(3)));
        assertTrue(threeSteps.getMessage().contains("mr"), threeSteps.getMessage());
        assertTrue(threeSteps.getMessage().contains("3"), threeSteps.getMessage());
        assertEquals(2, loader.parse(mapReduce(2)).steps().size());
    }

    /** The rework gate, with whatever onFail body the case under test needs. */
    private static String rework(String onFailBody) {
        return """
                name: r
                strategy: rework
                steps:
                  - id: work
                    template: generic
                    task: do it
                  - id: gate
                    template: generic
                    task: judge it
                    onFail:
                """ + onFailBody;
    }

    @Test
    void an_onFail_mode_is_parsed_and_defaults_to_retry() {
        assertEquals(ReworkMode.RESUME,
                loader.parse(rework("      retry: work\n      mode: resume\n")).steps().get(1).onFail().mode());
        assertEquals(ReworkMode.RETRY,
                loader.parse(rework("      retry: work\n")).steps().get(1).onFail().mode(),
                "a workflow written before the key existed keeps the behaviour it was proven with");
    }

    @Test
    void an_unknown_onFail_mode_is_refused_rather_than_treated_as_retry() {
        // A misspelled mode meaning "retry" is how a workflow ends up doing the one thing its
        // author wrote the key to avoid — and it would only show up on the run that needed it.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> loader.parse(rework("      retry: work\n      mode: continue\n")));
        assertTrue(thrown.getMessage().contains("continue"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("resume"), thrown.getMessage());
    }

    @Test
    void maxAttempts_is_defaulted_at_parse_so_the_bound_is_visible_before_the_run() {
        assertEquals(OnFail.DEFAULT_MAXIMUM_ATTEMPTS,
                loader.parse(rework("      retry: work\n")).steps().get(1).onFail().maximumAttempts());
        assertEquals(5,
                loader.parse(rework("      retry: work\n      maxAttempts: 5\n")).steps().get(1).onFail().maximumAttempts());
    }

    @Test
    void an_unknown_strategy_names_the_ones_that_exist() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> loader.parse("name: x\nstrategy: sequential\nsteps: []\n"));
        assertTrue(thrown.getMessage().contains("linear"), thrown.getMessage());
    }

    @Test
    void a_bare_parameter_name_gets_defaults_and_a_label() {
        Workflow workflow = loader.parse("name: p\nstrategy: linear\nparameters: [goal]\nsteps: []\n");
        assertEquals("goal", workflow.parameters().get(0).name());
        assertEquals("goal", workflow.parameters().get(0).labelOrName());
        assertEquals("text", workflow.parameters().get(0).type());
    }
}
