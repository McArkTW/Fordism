package tw.mcark.tony.fordism.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two guarantees {@link PrivateFile} exists to make, tested where they are breakable: a write
 * that fails, and a secret's permissions.
 *
 * <p>The interesting question is never "does a good write land" — it is what a write that goes
 * wrong leaves behind, because the stores on top of this read an unparseable file as "there is no
 * such record". A truncated credential does not look broken to an operator; it looks deleted.
 */
class PrivateFileTest {

    @TempDir
    Path directory;

    @Test
    void a_write_that_fails_leaves_the_previous_contents_untouched() throws IOException {
        Path file = directory.resolve("GITHUB_TOKEN.json");
        PrivateFile.write(file, "{\"value\":\"ghp-real\"}");

        // Block the staging path with something that cannot be replaced, so the write fails after
        // the point where an in-place writer would already have truncated the live file.
        Path staged = directory.resolve("GITHUB_TOKEN.json.tmp");
        Files.createDirectory(staged);
        Files.writeString(staged.resolve("occupied"), "in the way");

        assertThrows(IOException.class, () -> PrivateFile.write(file, "{\"value\":\"replacement\"}"));
        assertEquals("{\"value\":\"ghp-real\"}", Files.readString(file),
                "a failed write destroyed the secret it was replacing");
    }

    @Test
    void nothing_is_left_beside_the_file_afterwards() throws IOException {
        Path file = directory.resolve("TL_TOKEN.json");
        PrivateFile.write(file, "first");
        PrivateFile.write(file, "second");

        assertEquals("second", Files.readString(file));
        assertFalse(Files.exists(directory.resolve("TL_TOKEN.json.tmp")),
                "a temp file survived the write, holding a copy of the secret");
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count(), "the write left more than the file it was asked to write");
        }
    }

    @Test
    void a_secret_is_owner_readable_only_from_the_first_byte() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "no POSIX permissions on this filesystem");
        Path file = directory.resolve("SECRET.json");
        PrivateFile.write(file, "{\"value\":\"never-world-readable\"}");

        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), mode,
                "a secret was left readable by somebody other than its owner: " + mode);
    }

    @Test
    void replacing_an_existing_file_keeps_it_owner_readable_only() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "no POSIX permissions on this filesystem");
        Path file = directory.resolve("SECRET.json");
        Files.writeString(file, "written the old way, world-readable");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        PrivateFile.write(file, "{\"value\":\"replaced\"}");

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
                "replacing a file that was already world-readable left it that way");
    }

    @Test
    void the_parent_directory_is_created_when_it_does_not_exist_yet() throws IOException {
        Path file = directory.resolve("nested/deeper/KEY.json");
        PrivateFile.write(file, "value");
        assertTrue(Files.isRegularFile(file));
    }

}
