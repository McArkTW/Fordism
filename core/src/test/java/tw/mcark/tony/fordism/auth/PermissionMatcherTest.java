package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The matching rule, driven from {@code permission-matcher-vectors.json}.
 *
 * <p>The vectors live in a file rather than in this class because the app's TypeScript matcher
 * (which decides whether to draw a button) has to agree with this one (which decides whether the
 * request behind it is allowed). A rule written twice drifts; a rule read twice from one file
 * cannot.
 */
class PermissionMatcherTest {
    private static final Gson GSON = new Gson();
    private static final Type VECTORS = new TypeToken<List<Vector>>() {}.getType();

    /** One row of the shared file. */
    record Vector(String grant, String required, boolean expected) {}

    private static List<Vector> vectors() {
        InputStream stream = PermissionMatcherTest.class.getClassLoader()
                .getResourceAsStream("permission-matcher-vectors.json");
        assertNotNull(stream, "permission-matcher-vectors.json is missing from the test resources");
        return GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), VECTORS);
    }

    @Test
    void every_shared_vector_matches_as_written() {
        for (Vector vector : vectors()) {
            assertEquals(vector.expected(),
                    PermissionMatcher.matches(vector.grant(), vector.required()),
                    vector.grant() + " over " + vector.required());
        }
    }

    @Test
    void the_file_still_pins_the_cases_that_matter() {
        // A vector file the suite reads but nobody maintains is worse than no file — if a case is
        // deleted, this fails rather than the run getting quietly easier.
        assertEquals(11, vectors().size());
    }

    @Test
    void a_wildcard_is_trailing_only_so_a_grant_can_be_read_left_to_right() {
        assertFalse(PermissionMatcher.matches("*.read", "workflow.read"));
        assertFalse(PermissionMatcher.matches("work*", "workflow.read"));
        assertFalse(PermissionMatcher.matches(".*", "workflow.read"));
    }

    @Test
    void a_subtree_grant_stops_at_a_dot_not_at_a_character_count() {
        assertTrue(PermissionMatcher.matches("run.*", "run.workspace.download"));
        assertFalse(PermissionMatcher.matches("run.*", "runner.read"));
        assertFalse(PermissionMatcher.matches("run.*", "run"));
    }

    @Test
    void a_missing_or_empty_side_never_matches() {
        assertFalse(PermissionMatcher.matches(null, "run.read"));
        assertFalse(PermissionMatcher.matches("run.*", null));
        assertFalse(PermissionMatcher.matches("  ", "run.read"));
    }

    @Test
    void the_union_of_a_users_grants_is_what_gets_asked() {
        assertTrue(PermissionMatcher.anyMatches(Set.of("workflow.read", "run.*"), Permission.RUN_ANSWER));
        assertFalse(PermissionMatcher.anyMatches(Set.of("workflow.read", "run.read"), Permission.RUN_ANSWER));
        assertFalse(PermissionMatcher.anyMatches(Set.of(), Permission.WORKFLOW_READ));
    }
}
