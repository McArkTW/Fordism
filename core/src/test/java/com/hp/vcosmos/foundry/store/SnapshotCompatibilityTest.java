package com.hp.vcosmos.foundry.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.Gson;
import com.hp.vcosmos.foundry.agentprofile.AgentTool;
import com.hp.vcosmos.foundry.model.run.WorkflowRunState;
import com.hp.vcosmos.foundry.model.task.TaskMode;
import com.hp.vcosmos.foundry.model.task.TaskState;
import org.junit.jupiter.api.Test;

/**
 * The paused state has been renamed twice. Gson maps an unknown enum constant to null rather than
 * throwing, so a snapshot written by an older build used to restore a task with no state at all —
 * and the first orchestrator to switch on it failed the run.
 */
class SnapshotCompatibilityTest {
    private static final Gson GSON = new Gson();

    @Test
    void a_snapshot_written_before_either_rename_still_restores() {
        assertEquals(TaskState.ASKED, GSON.fromJson("\"NEEDS_RESCUE\"", TaskState.class));
        assertEquals(TaskState.ASKED, GSON.fromJson("\"NEEDS_HUMAN\"", TaskState.class));
        assertEquals(WorkflowRunState.ASKED, GSON.fromJson("\"NEEDS_RESCUE\"", WorkflowRunState.class));
        assertEquals(WorkflowRunState.ASKED, GSON.fromJson("\"NEEDS_HUMAN\"", WorkflowRunState.class));
    }

    @Test
    void the_current_name_reads_and_is_what_gets_written_back() {
        assertEquals(TaskState.ASKED, GSON.fromJson("\"ASKED\"", TaskState.class));
        assertEquals("\"ASKED\"", GSON.toJson(TaskState.ASKED));
        assertEquals("\"ASKED\"", GSON.toJson(WorkflowRunState.ASKED));
    }

    @Test
    void a_task_mode_written_by_an_older_build_still_restores() {
        // mode was a plain String until it became an enum; every snapshot on disk says "work" or
        // "resume" in lowercase, and an unrecognised constant would restore as null.
        assertEquals(TaskMode.WORK, GSON.fromJson("\"work\"", TaskMode.class));
        assertEquals(TaskMode.RESUME, GSON.fromJson("\"resume\"", TaskMode.class));
        assertEquals("\"work\"", GSON.toJson(TaskMode.WORK));
        assertEquals("\"resume\"", GSON.toJson(TaskMode.RESUME));
    }

    @Test
    void the_mode_the_agent_entrypoint_reads_matches_the_persisted_token() {
        // AGENT_MODE and the snapshot must not drift apart — the entrypoint branches on this word.
        for (TaskMode mode : TaskMode.values()) {
            assertEquals(GSON.toJson(mode), "\"" + mode.wireName() + "\"");
        }
    }

    @Test
    void an_agent_profile_written_before_the_tool_enum_still_reads() {
        // Stored per profile as a hyphenated string, and submitted that way by the API.
        assertEquals(AgentTool.CLAUDE_CODE, GSON.fromJson("\"claude-code\"", AgentTool.class));
        assertEquals(AgentTool.QWEN_CODE, GSON.fromJson("\"qwen-code\"", AgentTool.class));
        assertEquals("\"qwen-code\"", GSON.toJson(AgentTool.QWEN_CODE));
        for (AgentTool tool : AgentTool.values()) {
            assertEquals(GSON.toJson(tool), "\"" + tool.wireName() + "\"");
        }
    }

    @Test
    void a_profile_with_no_tool_at_all_defaults_rather_than_nulls() {
        // The field postdates the store; older records simply lack it.
        assertEquals(AgentTool.CLAUDE_CODE, AgentTool.from(null));
        assertEquals(AgentTool.CLAUDE_CODE, AgentTool.from(""));
        assertEquals(AgentTool.CLAUDE_CODE, AgentTool.from("something-else"));
        assertEquals(AgentTool.QWEN_CODE, AgentTool.from("qwen-code"));
    }

    @Test
    void every_other_state_round_trips() {
        for (TaskState state : TaskState.values()) {
            assertNotNull(GSON.fromJson(GSON.toJson(state), TaskState.class), state.name());
            assertEquals(state, GSON.fromJson(GSON.toJson(state), TaskState.class));
        }
        for (WorkflowRunState state : WorkflowRunState.values()) {
            assertEquals(state, GSON.fromJson(GSON.toJson(state), WorkflowRunState.class));
        }
    }
}
