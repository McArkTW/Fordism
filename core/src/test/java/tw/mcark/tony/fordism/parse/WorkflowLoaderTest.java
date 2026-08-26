package tw.mcark.tony.fordism.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
