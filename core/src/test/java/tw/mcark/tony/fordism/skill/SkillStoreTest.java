package tw.mcark.tony.fordism.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The library as a user edits it: write a skill, replace its folder, delete it. The folder upload
 * carries a path per file that the browser chose, so it gets the same treatment as a zip entry.
 */
class SkillStoreTest {

    @TempDir
    Path directory;

    private SkillStore skills;
    private Path root;

    @BeforeEach
    void setUp() {
        root = directory.resolve("skills");
        skills = new SkillStore(root, new SkillState(directory.resolve("state")));
    }

    @Test
    void a_written_skill_is_listed_with_its_frontmatter_description() throws IOException {
        skills.write("access/github", "---\ndescription: Open a PR\n---\n\nBody.\n");

        List<SkillView> listed = skills.list();

        assertEquals(1, listed.size());
        assertEquals("access/github", listed.get(0).name());
        assertEquals("Open a PR", listed.get(0).description());
    }

    /**
     * A folded block scalar. Real skills are written this way — two of the nineteen in
     * {@code anthropics/skills} are — and taking the {@code >} as the value put a literal
     * "&gt;" in the list where the sentence belonged.
     */
    @Test
    void a_folded_block_scalar_description_is_read_not_shown_as_its_indicator() throws IOException {
        skills.write("access/github", """
                ---
                name: github
                description: >
                  Open a pull request against a repo the agent
                  already has a token for.
                ---

                Body.
                """);

        assertEquals("Open a pull request against a repo the agent already has a token for.",
                skills.read("access/github").description());
    }

    @Test
    void a_literal_block_scalar_description_is_read_too() throws IOException {
        skills.write("access/gitlab", "---\ndescription: |-\n  Open a merge request.\n---\n");

        assertEquals("Open a merge request.", skills.read("access/gitlab").description());
    }

    /** The block ends where the indentation does — the next key is not part of the description. */
    @Test
    void a_block_scalar_description_stops_at_the_next_key() throws IOException {
        skills.write("access/bitbucket", """
                ---
                description: >
                  Only this line.
                license: Apache-2.0
                ---
                """);

        assertEquals("Only this line.", skills.read("access/bitbucket").description());
    }

    @Test
    void writing_a_skill_again_replaces_its_content() throws IOException {
        skills.write("edit-me", "---\ndescription: First\n---\n");
        skills.write("edit-me", "---\ndescription: Second\n---\n");

        assertEquals("Second", skills.read("edit-me").description());
    }

    @Test
    void deleting_a_skill_removes_its_folder() throws IOException {
        skills.write("gone", "---\ndescription: Bye\n---\n");

        skills.delete("gone");

        assertFalse(Files.exists(root.resolve("gone")));
        assertTrue(skills.list().isEmpty());
    }

    @Test
    void an_uploaded_folder_keeps_its_nested_paths() throws IOException {
        skills.writeFolder("bundled",
                List.of("SKILL.md", "scripts/run.sh"),
                streams("---\ndescription: Bundled\n---\n", "echo hi\n"));

        assertEquals("echo hi\n", Files.readString(root.resolve("bundled/scripts/run.sh")));
        assertTrue(skills.read("bundled").files().contains("scripts/run.sh"));
    }

    /** The whole point of clearing first: a file the user deleted upstream must not survive. */
    @Test
    void re_uploading_a_folder_drops_a_file_that_is_no_longer_in_it() throws IOException {
        skills.writeFolder("bundled",
                List.of("SKILL.md", "stale.txt"),
                streams("---\ndescription: One\n---\n", "old"));

        skills.writeFolder("bundled", List.of("SKILL.md"), streams("---\ndescription: Two\n---\n"));

        assertFalse(Files.exists(root.resolve("bundled/stale.txt")));
        assertEquals("Two", skills.read("bundled").description());
    }

    @Test
    void a_folder_without_a_skill_md_is_refused_and_leaves_nothing_behind() {
        assertThrows(IllegalArgumentException.class, () ->
                skills.writeFolder("no-contract", List.of("notes.txt"), streams("hello")));

        assertFalse(Files.exists(root.resolve("no-contract")));
    }

    /** An upload names its own paths, so it is exactly as untrusted as a zip entry. */
    @Test
    void an_uploaded_path_that_walks_out_of_the_skill_is_dropped() throws IOException {
        skills.writeFolder("guarded",
                List.of("SKILL.md", "../../escape.txt"),
                streams("---\ndescription: Guarded\n---\n", "owned"));

        assertFalse(Files.exists(directory.resolve("escape.txt")));
        assertTrue(Files.exists(root.resolve("guarded/SKILL.md")));
    }

    @Test
    void a_skill_name_that_walks_out_of_the_library_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> skills.write("../escape", "x"));
    }

    private static List<InputStream> streams(String... contents) {
        return java.util.Arrays.stream(contents)
                .map(c -> (InputStream) new ByteArrayInputStream(c.getBytes(StandardCharsets.UTF_8)))
                .toList();
    }
}
