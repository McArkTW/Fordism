package tw.mcark.tony.fordism.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The grant rule, as a test rather than a paragraph: an agent receives the credentials its Agent
 * Template declared, and nothing else.
 */
class CredentialStoreTest {

    @TempDir
    Path directory;

    private CredentialStore credentials;

    @BeforeEach
    void setUp() throws IOException {
        credentials = new CredentialStore(directory);
        credentials.save("GITHUB_TOKEN", "ghp-real", "for pushes");
        credentials.save("TL_TOKEN", "tl-real", "TestLogic");
        credentials.save("EMPTY_TOKEN", "", "declared but never filled in");
    }

    @Test
    void a_task_receives_exactly_the_keys_its_template_declared() {
        assertEquals(Map.of("TL_TOKEN", "tl-real"), credentials.values(List.of("TL_TOKEN")));
        assertEquals(Map.of("GITHUB_TOKEN", "ghp-real", "TL_TOKEN", "tl-real"),
                credentials.values(List.of("GITHUB_TOKEN", "TL_TOKEN")));
    }

    @Test
    void a_template_that_declared_nothing_gets_nothing() {
        // The whole grant model rests on this: default is no access, not all access.
        assertTrue(credentials.values(List.of()).isEmpty());
        assertTrue(credentials.values(null).isEmpty());
    }

    @Test
    void a_key_that_does_not_exist_or_holds_no_value_is_simply_not_passed() {
        assertTrue(credentials.values(List.of("NO_SUCH_TOKEN")).isEmpty());
        assertTrue(credentials.values(List.of("EMPTY_TOKEN")).isEmpty());
        assertEquals(Map.of("TL_TOKEN", "tl-real"),
                credentials.values(List.of("TL_TOKEN", "NO_SUCH_TOKEN", "EMPTY_TOKEN")));
    }

    @Test
    void the_browser_facing_views_never_carry_a_value() {
        assertFalse(credentials.list().toString().contains("ghp-real"));
        assertFalse(credentials.read("GITHUB_TOKEN").orElseThrow().toString().contains("ghp-real"));
        assertTrue(credentials.read("GITHUB_TOKEN").orElseThrow().hasValue());
        assertFalse(credentials.read("EMPTY_TOKEN").orElseThrow().hasValue());
        assertTrue(credentials.read("NO_SUCH_TOKEN").isEmpty());
    }

    @Test
    void saving_a_blank_value_keeps_the_stored_secret_so_a_note_can_be_edited_alone() throws IOException {
        credentials.save("TL_TOKEN", "", "a better note");
        assertEquals(Map.of("TL_TOKEN", "tl-real"), credentials.values(List.of("TL_TOKEN")));
        assertEquals("a better note", credentials.read("TL_TOKEN").orElseThrow().note());
    }

    @Test
    void a_value_with_a_line_break_is_refused_rather_than_silently_corrupted() {
        // The launcher's --env-file writer turns a newline into a space, which would make the token
        // fail authentication for reasons no log explains.
        assertThrows(IllegalArgumentException.class,
                () -> credentials.save("TL_TOKEN", "first\nsecond", "note"));
        assertEquals(Map.of("TL_TOKEN", "tl-real"), credentials.values(List.of("TL_TOKEN")));
    }

    @Test
    void a_key_that_is_not_an_environment_variable_name_is_refused() {
        assertFalse(CredentialStore.isValidKey("not-a-var"));
        assertFalse(CredentialStore.isValidKey("9LIVES"));
        assertTrue(CredentialStore.isValidKey("_OK2"));
        assertThrows(IllegalArgumentException.class, () -> credentials.save("not-a-var", "x", ""));
    }
}
