package tw.mcark.tony.fordism.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import tw.mcark.tony.fordism.agentprofile.AgentTool;
import tw.mcark.tony.fordism.model.workflow.Workflow;

/**
 * Where {@code continues} may stand. A continuing step needs exactly one agent before it whose bench
 * is free; every shape where there is none, or several, is refused when the workflow is read rather
 * than when the step is reached.
 */
class ContinuesLoaderTest {
    private final WorkflowLoader loader = new WorkflowLoader();

    private void refused(String yaml, String expected) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> loader.parse(yaml));
        assertTrue(thrown.getMessage().contains(expected), thrown.getMessage());
    }

    @Test
    void a_linear_step_continues_the_step_before_it() {
        Workflow workflow = loader.parse("""
                name: l
                steps:
                  - id: propose
                    task: propose it
                  - id: apply
                    task: apply it
                    continues: true
                """);
        assertTrue(workflow.steps().get(1).continues());
        assertFalse(workflow.steps().get(0).continues());
        assertEquals(OptionalInt.of(0), workflow.continuedStep(1));
        assertEquals(OptionalInt.empty(), workflow.continuedStep(0));
    }

    @Test
    void the_first_step_has_nothing_to_continue() {
        refused("""
                name: l
                steps:
                  - id: first
                    task: go
                    continues: true
                """, "first step");
    }

    @Test
    void a_map_step_is_many_agents() {
        refused("""
                name: m
                strategy: map-reduce
                parameters: [items]
                steps:
                  - id: map
                    task: one item
                    forEach: items
                    continues: true
                  - id: reduce
                    task: all of them
                """, "map-reduce");
    }

    @Test
    void a_reduce_has_many_map_agents_to_choose_from() {
        refused("""
                name: m
                strategy: map-reduce
                parameters: [items]
                steps:
                  - id: map
                    task: one item
                    forEach: items
                  - id: reduce
                    task: all of them
                    continues: true
                """, "map-reduce");
    }

    @Test
    void a_graph_join_has_several_agents_to_choose_from() {
        refused("""
                name: g
                strategy: graph
                steps:
                  - id: a
                    task: a
                  - id: b
                    task: b
                  - id: join
                    task: both
                    dependsOn: [a, b]
                    continues: true
                """, "dependsOn [a, b]");
    }

    @Test
    void a_graph_root_has_no_dependency_to_continue() {
        refused("""
                name: g
                strategy: graph
                steps:
                  - id: a
                    task: a
                  - id: b
                    task: b
                    continues: true
                """, "no dependsOn");
    }

    @Test
    void a_graph_step_with_one_dependency_continues_that_dependency() {
        Workflow workflow = loader.parse("""
                name: g
                strategy: graph
                steps:
                  - id: revise
                    task: revise it
                    dependsOn: [draft]
                    continues: true
                  - id: draft
                    task: draft it
                """);
        assertEquals(OptionalInt.of(1), workflow.continuedStep(0));
    }

    @Test
    void two_steps_cannot_continue_one_agent() {
        refused("""
                name: g
                strategy: graph
                steps:
                  - id: draft
                    task: draft it
                  - id: left
                    task: one way
                    dependsOn: [draft]
                    continues: true
                  - id: right
                    task: the other
                    dependsOn: [draft]
                    continues: true
                """, "both continue");
    }

    @Test
    void the_reconciler_generator_cannot_continue() {
        refused("""
                name: r
                strategy: reconciler
                generator:
                  task: converge
                  continues: true
                """, "generator cannot continue");
    }

    @Test
    void a_rework_step_may_continue_the_step_before_it() {
        Workflow workflow = loader.parse("""
                name: r
                strategy: rework
                steps:
                  - id: write
                    task: write it
                  - id: revise
                    task: revise it
                    continues: true
                  - id: gate
                    task: judge it
                    onFail:
                      retry: write
                """);
        assertEquals(OptionalInt.of(0), workflow.continuedStep(1));
    }

    /** Two templates on the same profile, one on another, and a template that does not exist yet. */
    private static final StepAgents AGENTS = new StepAgents() {
        public Optional<StepAgent> of(String template) {
            return switch (template == null ? "" : template) {
                case "writer", "reviser" -> Optional.of(new StepAgent("claude", AgentTool.CLAUDE_CODE));
                case "qwen-reviser" -> Optional.of(new StepAgent("local", AgentTool.QWEN_CODE));
                default -> Optional.empty();
            };
        }

        public Optional<StepAgent> bare() {
            return Optional.of(new StepAgent("", AgentTool.CLAUDE_CODE));
        }
    };

    private Workflow pair(String second) {
        return loader.parse("""
                name: p
                steps:
                  - id: write
                    template: writer
                    task: write it
                  - id: revise
                    template: %s
                    task: revise it
                    continues: true
                """.formatted(second));
    }

    @Test
    void a_continuing_step_on_another_profile_is_refused_at_load() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowLoader.checkContinuedAgents(pair("qwen-reviser"), AGENTS, null));
        assertTrue(thrown.getMessage().contains("qwen-code"), thrown.getMessage());
    }

    @Test
    void a_different_template_on_the_same_profile_is_fine() {
        assertDoesNotThrow(() -> WorkflowLoader.checkContinuedAgents(pair("reviser"), AGENTS, null));
    }

    @Test
    void a_template_not_resolvable_at_load_is_checked_at_run_creation() {
        Workflow workflow = pair("${reviser}");
        assertDoesNotThrow(() -> WorkflowLoader.checkContinuedAgents(workflow, AGENTS, null));
        assertDoesNotThrow(() -> WorkflowLoader.checkContinuedAgents(workflow, AGENTS,
                Map.of("reviser", "reviser")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowLoader.checkContinuedAgents(workflow, AGENTS,
                Map.of("reviser", "qwen-reviser")));
    }

    @Test
    void a_template_that_still_does_not_exist_at_run_creation_runs_bare() {
        // Unknown at load: not refused. At the run it runs on the default backend, which is not the
        // writer's profile — so it is refused then, before a task exists.
        Workflow workflow = pair("not-made-yet");
        assertDoesNotThrow(() -> WorkflowLoader.checkContinuedAgents(workflow, AGENTS, null));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowLoader.checkContinuedAgents(workflow, AGENTS, Map.of()));
    }
}
