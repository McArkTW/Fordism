package tw.mcark.tony.fordism.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import tw.mcark.tony.fordism.model.workflow.ArtifactAccess;
import tw.mcark.tony.fordism.model.workflow.Step;
import tw.mcark.tony.fordism.model.workflow.Workflow;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * THE RULE: a step may write the run's artifact only when nothing else can run beside it.
 *
 * <p>Decided at load time from the strategy and {@code dependsOn}, so it is asserted on the parsed
 * workflow — what the editor outline draws and what the launcher mounts from.
 */
class ArtifactAccessTest {
    private static final ArtifactAccess WRITE = ArtifactAccess.WRITE;
    private static final ArtifactAccess READ = ArtifactAccess.READ_ONLY;

    private final WorkflowLoader loader = new WorkflowLoader();

    /** Step id to access, in declaration order. */
    private Map<String, ArtifactAccess> access(String yaml) {
        Workflow workflow = loader.parse(yaml);
        Map<String, ArtifactAccess> out = new LinkedHashMap<>();
        for (Step step : workflow.steps()) {
            out.put(step.id(), step.artifact());
        }
        return out;
    }

    @Test
    void every_linear_step_writes_because_they_run_one_at_a_time() {
        assertEquals(Map.of("build", WRITE, "check", WRITE), access("""
                name: s
                strategy: linear
                steps:
                  - id: build
                    template: generic
                    task: build it
                  - id: check
                    template: generic
                    task: check it
                """));
    }

    @Test
    void every_rework_step_writes() {
        assertEquals(Map.of("build", WRITE, "check", WRITE), access("""
                name: r
                strategy: rework
                steps:
                  - id: build
                    template: generic
                    task: build it
                  - id: check
                    template: generic
                    task: judge it
                    onFail:
                      retry: build
                """));
    }

    @Test
    void every_conditional_step_writes() {
        assertEquals(Map.of("classify", WRITE, "fix", WRITE), access("""
                name: c
                strategy: conditional
                steps:
                  - id: classify
                    template: generic
                    task: is it broken?
                  - id: fix
                    template: generic
                    task: fix it
                    when: classify.result == 'yes'
                """));
    }

    @Test
    void map_items_read_and_the_reduce_writes() {
        // The items run side by side, so each hands its work back through result/; the reduce is
        // alone, gets the tree writable, and merges.
        assertEquals(Map.of("map", READ, "reduce", WRITE), access("""
                name: m
                strategy: map-reduce
                parameters: [items]
                steps:
                  - id: map
                    template: generic
                    task: handle ${item}
                    forEach: ${items}
                  - id: reduce
                    template: generic
                    task: merge them
                """));
    }

    @Test
    void a_diamond_lets_only_its_ends_write() {
        //      scaffold
        //      /      \
        //   front    back      <- could run at once: read-only
        //      \      /
        //       join
        Map<String, ArtifactAccess> access = access("""
                name: d
                strategy: graph
                steps:
                  - id: scaffold
                    template: generic
                    task: lay it out
                  - id: front
                    template: generic
                    task: the UI
                    dependsOn: [scaffold]
                  - id: back
                    template: generic
                    task: the API
                    dependsOn: [scaffold]
                  - id: join
                    template: generic
                    task: merge both
                    dependsOn: [front, back]
                """);
        assertEquals(WRITE, access.get("scaffold"));
        assertEquals(READ, access.get("front"));
        assertEquals(READ, access.get("back"));
        assertEquals(WRITE, access.get("join"), "downstream of everything, so alone when it runs");
    }

    @Test
    void two_independent_chains_are_read_only_end_to_end() {
        // a1 -> a2 and b1 -> b2 share no edge, so any step of one chain can run beside any step of
        // the other. Being ordered against its own chain is not enough.
        assertEquals(Map.of("a1", READ, "a2", READ, "b1", READ, "b2", READ), access("""
                name: two
                strategy: graph
                steps:
                  - id: a1
                    template: generic
                    task: a1
                  - id: a2
                    template: generic
                    task: a2
                    dependsOn: [a1]
                  - id: b1
                    template: generic
                    task: b1
                  - id: b2
                    template: generic
                    task: b2
                    dependsOn: [b1]
                """));
    }

    @Test
    void a_graph_that_is_a_chain_writes_throughout() {
        // Ordered through transitive dependsOn too: c reaches a only through b.
        assertEquals(Map.of("a", WRITE, "b", WRITE, "c", WRITE), access("""
                name: chain
                strategy: graph
                steps:
                  - id: a
                    template: generic
                    task: a
                  - id: b
                    template: generic
                    task: b
                    dependsOn: [a]
                  - id: c
                    template: generic
                    task: c
                    dependsOn: [b]
                """));
    }

    @Test
    void the_reconciler_generator_writes() {
        // Its child runs are other runs: they get a copy through artifactFrom, never this tree.
        Workflow workflow = loader.parse("""
                name: rec
                strategy: reconciler
                maxIterations: 3
                generator:
                  template: generic
                  task: converge
                """);
        assertEquals(WRITE, workflow.generator().artifact());
    }
}
