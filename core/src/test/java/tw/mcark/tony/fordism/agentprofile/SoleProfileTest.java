package tw.mcark.tony.fordism.agentprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The out-of-the-box rule: a template with no Agent Profile of its own resolves to the sole
 * existing profile — so a fresh install runs the bundled example workflows the moment its first
 * profile is created — and to nothing once the choice would be ambiguous.
 */
class SoleProfileTest {

    @TempDir
    Path directory;

    @Test
    void no_profiles_means_nothing_to_resolve_to() {
        assertTrue(new AgentProfileStore(directory).sole().isEmpty());
    }

    @Test
    void exactly_one_profile_is_the_default_for_everything() throws IOException {
        AgentProfileStore profiles = new AgentProfileStore(directory);
        profiles.create(new AgentProfile(null, "anthropic", "https://api.anthropic.com", "sk-test",
                "claude-sonnet-5", AgentTool.CLAUDE_CODE));
        Optional<AgentProfile> sole = profiles.sole();
        assertEquals("anthropic", sole.orElseThrow().name());
    }

    @Test
    void two_profiles_are_ambiguous_and_resolve_to_nothing() throws IOException {
        AgentProfileStore profiles = new AgentProfileStore(directory);
        profiles.create(new AgentProfile(null, "anthropic", "https://api.anthropic.com", "sk-test",
                "claude-sonnet-5", AgentTool.CLAUDE_CODE));
        profiles.create(new AgentProfile(null, "ollama", "http://gpu-host:11434", "",
                "qwen3", AgentTool.QWEN_CODE));
        assertTrue(profiles.sole().isEmpty());
    }
}
