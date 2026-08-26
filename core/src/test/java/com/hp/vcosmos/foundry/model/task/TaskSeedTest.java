package com.hp.vcosmos.foundry.model.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hp.vcosmos.foundry.model.workflow.Step;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskSeedTest {

    private static Step step(String template, String task, boolean includePrevious) {
        return new Step("s0", template, task, includePrevious, List.of(), null, null, null,
                TaskConfiguration.defaults());
    }

    @Test
    void a_step_resolves_its_placeholders_against_the_run_parameters() {
        TaskSeed seed = TaskSeed.of(2, step("generic", "Summarize ${topic} for ${who}", false),
                Map.of("topic", "backpressure", "who", "the team"), null, 1);

        assertEquals(2, seed.stepIndex());
        assertEquals("Summarize backpressure for the team", seed.taskText());
        assertEquals(1, seed.attempt());
    }

    @Test
    void a_parameter_supplied_as_null_becomes_empty() {
        assertEquals("do  now", TaskSeed.substitute("do ${nulled} now",
                java.util.Collections.singletonMap("nulled", null)));
    }

    @Test
    void a_placeholder_the_run_never_supplied_is_left_standing() {
        // It reaches the agent as the literal ${missing}. That is deliberate: it shows up in the
        // prompt and the transcript, which is how you learn the run was started without it.
        assertEquals("do ${missing} now", TaskSeed.substitute("do ${missing} now", Map.of()));
    }

    @Test
    void a_null_text_stays_null_so_a_template_less_step_is_still_seedable() {
        assertNull(TaskSeed.substitute(null, Map.of("a", "b")));
        assertNull(TaskSeed.of(0, step(null, null, false), Map.of(), null, 1).template());
    }

    @Test
    void the_step_config_and_previous_result_flag_ride_along() {
        TaskSeed seed = TaskSeed.of(1, step("gate", "judge it", true), Map.of(), "/workspaces/prev", 3);
        assertTrue(seed.includePreviousResult());
        assertEquals("/workspaces/prev", seed.previousWorkspace());
        assertEquals(3, seed.attempt());
        assertEquals(TaskConfiguration.defaults(), seed.config());
    }

    @Test
    void a_loop_can_force_the_previous_result_in_without_the_step_declaring_it() {
        TaskSeed seed = TaskSeed.of(1, step("generic", "keep going", false), Map.of(), "/workspaces/prev", 1)
                .includingPreviousResult();
        assertTrue(seed.includePreviousResult());
        assertEquals("/workspaces/prev", seed.previousWorkspace());
    }
}
