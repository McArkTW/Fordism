package tw.mcark.tony.fordism.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The zip-slip guard, as a test rather than a comment: an uploaded task.zip decides only what lands
 * in the task's own {@code task/} directory. An entry that walks out of it is dropped — never
 * written, and never at the cost of the entries around it.
 */
class WorkspaceStagerZipSlipTest {

    @TempDir
    Path directory;

    /** The workspace layout the stager unzips into: <workspace>/task. */
    private Path destination;

    @BeforeEach
    void setUp() throws IOException {
        destination = directory.resolve("workspace/task");
        Files.createDirectories(destination);
    }

    @Test
    void an_entry_that_walks_out_of_the_destination_is_never_written() throws IOException {
        Path zip = zipWith("../../escape.txt", "owned");

        WorkspaceStager.unzipInto(zip, destination);

        assertFalse(Files.exists(directory.resolve("escape.txt")),
                "a ../ entry escaped the task directory");
        assertTrue(isEmpty(destination), "nothing should have been extracted at all");
    }

    @Test
    void a_traversal_hidden_behind_a_subdirectory_is_caught_too() throws IOException {
        // Rejecting names that merely START with ".." would let this one through.
        Path zip = zipWith("sub/../../escape.txt", "owned");

        WorkspaceStager.unzipInto(zip, destination);

        assertFalse(Files.exists(directory.resolve("workspace/escape.txt")));
        assertTrue(isEmpty(destination));
    }

    @Test
    void the_honest_entries_around_a_rejected_one_still_arrive() throws IOException {
        Path zip = directory.resolve("task.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            write(out, "input.csv", "id,name");
            write(out, "../../escape.txt", "owned");
            write(out, "nested/notes.md", "# notes");
        }

        WorkspaceStager.unzipInto(zip, destination);

        assertEquals("id,name", Files.readString(destination.resolve("input.csv")));
        assertEquals("# notes", Files.readString(destination.resolve("nested/notes.md")));
        assertFalse(Files.exists(directory.resolve("escape.txt")));
    }

    private Path zipWith(String entryName, String content) throws IOException {
        Path zip = directory.resolve("task.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            write(out, entryName, content);
        }
        return zip;
    }

    private static void write(ZipOutputStream out, String entryName, String content) throws IOException {
        out.putNextEntry(new ZipEntry(entryName));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }
}
