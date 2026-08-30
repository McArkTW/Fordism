package tw.mcark.tony.fordism.agentprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The class javadoc says an API key is stored owner-readable only. This is that sentence as a test,
 * because a guarantee written in prose is not enforced.
 *
 * <p>Stripping the key from every browser-facing view — which {@code AgentProfileView} does and
 * {@link SoleProfileTest}'s neighbours cover — protects one of the two ways out of this store. The
 * other is the file itself, on a volume every container in the compose stack can be pointed at.
 */
class ProfileKeyOnDiskTest {

    @TempDir
    Path directory;

    @Test
    void a_stored_api_key_is_readable_only_by_the_account_that_wrote_it() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "no POSIX permissions on this filesystem");
        AgentProfileStore profiles = new AgentProfileStore(directory);
        String id = profiles.create(new AgentProfile(null, "anthropic", "https://api.anthropic.com",
                "sk-secret", "claude-sonnet-5", AgentTool.CLAUDE_CODE));

        Path file = directory.resolve(id + ".json");
        assertTrue(Files.readString(file).contains("sk-secret"), "the key is on disk, as designed");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
                "an API key was left readable by somebody other than the account that wrote it");
    }

    @Test
    void an_edit_that_keeps_the_stored_key_does_not_widen_the_file() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "no POSIX permissions on this filesystem");
        AgentProfileStore profiles = new AgentProfileStore(directory);
        String id = profiles.create(new AgentProfile(null, "anthropic", "https://api.anthropic.com",
                "sk-secret", "claude-sonnet-5", AgentTool.CLAUDE_CODE));

        // A blank key means "keep the stored one" — the rewrite must be as private as the original.
        profiles.update(id, new AgentProfile(id, "anthropic-renamed", "https://api.anthropic.com",
                "", "claude-sonnet-5", AgentTool.CLAUDE_CODE));

        Path file = directory.resolve(id + ".json");
        assertTrue(Files.readString(file).contains("sk-secret"), "the kept key survived the rename");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
                "rewriting a profile widened the file holding its API key");
    }
}
