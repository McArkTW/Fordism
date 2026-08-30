package tw.mcark.tony.fordism.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * One file holding a secret, replaced whole or not at all, and readable only by the account that
 * wrote it.
 *
 * <p>Two guarantees, both of which the stores that hold API keys and credentials were missing:
 *
 * <ul>
 *   <li><b>Whole or not at all.</b> {@code Files.writeString} truncates the live file and then
 *       refills it. A kill, a full disk or a container stop inside that window leaves a
 *       zero-length or half-written file, and both secret stores read an unparseable file as
 *       "there is no such record" — so the credential disappears from the UI and every task that
 *       declared it launches without it. The bytes go to a sibling temp file, are forced to disk,
 *       and are moved onto the target, exactly as {@link JsonStateStore} and {@code JsonRecordFile}
 *       already do for state that matters less than this does.
 *   <li><b>0600 from the first byte.</b> Writing the target and narrowing it afterwards leaves the
 *       secret world-readable for the width of the write. The permissions are set on the temp file
 *       before any content reaches it, and a move carries them across.
 * </ul>
 *
 * <p>The temp file is a SIBLING because ATOMIC_MOVE only holds within a filesystem and these
 * directories are bind mounts. A filesystem that refuses ATOMIC_MOVE falls back to a plain replace
 * — still whole, because the temp file is already complete — and one that has no POSIX permissions
 * at all (dev on Windows) keeps the atomicity and skips the mode, which is the same trade
 * {@code CredentialStore} already made for its own chmod.
 */
public final class PrivateFile {

    private static final String OWNER_ONLY = "rw-------";

    private PrivateFile() {}

    /** Replace {@code file}'s contents with {@code content}. Creates the parent directory. */
    public static void write(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path staged = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            writeStaged(staged, content);
            move(staged, file);
        } catch (IOException e) {
            // A failed write must not leave a temp file behind holding the secret. Cleaning up is
            // not allowed to replace the reason the write failed, which is the useful half.
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // nothing further to try; the throw below is what the caller needs
            }
            throw e;
        }
    }

    private static void writeStaged(Path staged, String content) throws IOException {
        Files.deleteIfExists(staged);
        try {
            Files.createFile(staged, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(OWNER_ONLY)));
        } catch (UnsupportedOperationException e) {
            // non-POSIX filesystem (dev on Windows) — atomicity still holds, the mode does not apply
            Files.createFile(staged);
        }
        try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);   // the bytes must be on disk before the move, or the move outruns them
        }
    }

    private static void move(Path staged, Path file) throws IOException {
        try {
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
