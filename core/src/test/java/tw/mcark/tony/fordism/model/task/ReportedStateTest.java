package tw.mcark.tony.fordism.model.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What an agent claims about itself, and what the engine makes of a claim it cannot read. */
class ReportedStateTest {

    @Test
    void the_tokens_the_contract_documents() {
        assertEquals(ReportedState.FINISHED, ReportedState.from("finished"));
        assertEquals(ReportedState.FAILED, ReportedState.from("failed"));
        assertEquals(ReportedState.ASKED, ReportedState.from("asked"));
        assertEquals(ReportedState.RUNNING, ReportedState.from("running"));
    }

    @Test
    void a_token_is_read_however_the_agent_cased_or_padded_it() {
        assertEquals(ReportedState.FINISHED, ReportedState.from("FINISHED"));
        assertEquals(ReportedState.ASKED, ReportedState.from("  Asked  "));
    }

    @Test
    void anything_unrecognised_reads_as_still_running() {
        // Deliberate: the Reaper ends a task that never reports, not the parser. A token we cannot
        // read must not be mistaken for a finish.
        assertEquals(ReportedState.RUNNING, ReportedState.from(null));
        assertEquals(ReportedState.RUNNING, ReportedState.from(""));
        assertEquals(ReportedState.RUNNING, ReportedState.from("needs_rescue"));
        assertEquals(ReportedState.RUNNING, ReportedState.from("done"));
    }

    @Test
    void only_a_reported_finish_is_terminal() {
        assertFalse(ReportedState.RUNNING.isTerminal());
        assertTrue(ReportedState.FINISHED.isTerminal());
        assertTrue(ReportedState.FAILED.isTerminal());
        assertTrue(ReportedState.ASKED.isTerminal());
    }
}
