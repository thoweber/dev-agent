package guru.thomasweber.devagent.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Shared path canonicalization, bounded no-follow reads, and owner-only atomic file writes.
 */
public final class PathGuard {

    public static final int DEFAULT_MAX_TEXT_FILE_BYTES = 1_048_576;

    private PathGuard() {
    }

    public static Path toRealOrNormalized(Path path) {
        if (isNull(path)) {
            return null;
        }
        try {
            return path.toRealPath();
        } catch (IOException _) {
            return path.toAbsolutePath().normalize();
        }
    }

    public static String readRegularFileBounded(Path file, int maxBytes) throws IOException {
        if (isNull(file)) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (Files.isSymbolicLink(file)) {
            throw new SecurityException("Refusing to read through symbolic link: " + file);
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new SecurityException("File exceeds maximum allowed size (" + maxBytes + " bytes): " + file);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static Path createOwnerOnlyTempFile(Path parent, String prefix, String suffix) throws IOException {
        Path effectiveParent = parent;
        if (isNull(effectiveParent)) {
            effectiveParent = Path.of(System.getProperty("java.io.tmpdir"));
        }
        try {
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
            return Files.createTempFile(effectiveParent, prefix, suffix, attr);
        } catch (UnsupportedOperationException _) {
            return Files.createTempFile(effectiveParent, prefix, suffix);
        }
    }

    public static void writeOwnerOnlyAtomically(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (nonNull(parent)) {
            Files.createDirectories(parent);
        }
        Path tempFile = createOwnerOnlyTempFile(parent, target.getFileName() + "-", ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8, LinkOption.NOFOLLOW_LINKS);
            moveAtomically(tempFile, target);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
