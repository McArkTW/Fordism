package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The permission vocabulary, pinned to {@code permission-leaves.json}.
 *
 * <p>Same argument as {@code permission-matcher-vectors.json}: the app carries its own copy of this
 * list, because the grant editor previews which concrete permissions a pattern like {@code run.*}
 * would cover — and a preview built from a stale list quietly tells an administrator that a group
 * grants something it does not, or hides something it does. CI compares the two parsed files, and
 * this test is the other half: that core's copy is the enum and not a list somebody edited by hand.
 *
 * <p>It also fails when a permission is RENAMED, which is the point. A grant is a string a human
 * typed into a group and it is on disk; renaming a leaf silently drops whatever that grant covered.
 * The list changing is not a problem, but it must be a decision, with the upgrade note that goes
 * with it.
 */
class PermissionLeavesTest {
    private static final Gson GSON = new Gson();
    private static final Type LEAVES = new TypeToken<List<String>>() {}.getType();

    @Test
    void the_file_the_app_also_holds_is_exactly_this_enum() {
        InputStream stream = PermissionLeavesTest.class.getClassLoader()
                .getResourceAsStream("permission-leaves.json");
        assertNotNull(stream, "permission-leaves.json is missing from the test resources");
        List<String> published = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), LEAVES);

        List<String> declared = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            declared.add(permission.id());
        }
        declared.sort(String::compareTo);

        assertEquals(declared, published,
                "permission-leaves.json has drifted from the Permission enum — update it in core AND "
                        + "in app/src/app/auth/, and say so in the changelog if a leaf was renamed");
    }

    @Test
    void every_leaf_is_a_dotted_lowercase_name_a_grant_pattern_can_reach() {
        for (Permission permission : Permission.values()) {
            assertTrue(permission.id().matches("[a-z]+(\\.[a-z]+)+"),
                    permission.id() + " is not a dotted lowercase name");
            // A leaf must be reachable both exactly and by the subtree above it, or a group that
            // grants that subtree would not cover the thing the route asks for.
            assertTrue(PermissionMatcher.matches(permission.id(), permission.id()));
            String parent = permission.id().substring(0, permission.id().lastIndexOf('.'));
            assertTrue(PermissionMatcher.matches(parent + ".*", permission.id()),
                    parent + ".* must cover " + permission.id());
        }
    }
}
