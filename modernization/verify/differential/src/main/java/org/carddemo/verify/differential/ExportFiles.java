package org.carddemo.verify.differential;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * An export folder: every export's and the refusal mode's. The records hold full card numbers, so
 * the folder is owner-only (0700), checked by {@link #open(String, Path, OptionalLong)}, and each
 * file owner-only (0600). Each file is written under a new temporary name and, when complete,
 * linked onto its own name, which must not exist: nothing in the folder is ever replaced, and a
 * temporary name that exists is not this export's and is left alone. If any file fails, the files
 * already written are removed, so a folder never holds a partial export. The caller writes
 * {@code counts.json} last.
 */
final class ExportFiles {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> OWNER_ONLY = Collections.unmodifiableSet(EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    private static final Set<PosixFilePermission> GROUP_OR_OTHER = Collections.unmodifiableSet(EnumSet.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE));

    private final Path dir;
    private final List<Path> written = new ArrayList<>();

    private ExportFiles(Path dir) {
        this.dir = dir;
    }

    /** {@link #open(String, Path)} for {@code --export}. */
    static ExportFiles open(Path dir) {
        return open("--export", dir);
    }

    /** {@link #open(String, Path, OptionalLong)} with this process's user ID. */
    static ExportFiles open(String option, Path dir) {
        return open(option, dir, ProcessUid.current());
    }

    /**
     * The destination of {@code option}, checked and never repaired:
     * <ul>
     * <li>its parent must exist, be a directory, be owned by {@code uid} and have no group or other
     * write; nothing creates it;
     * <li>an existing destination must be an empty directory, not a link, owned by {@code uid}, with
     * no group or other permission; its mode is never changed;
     * <li>a missing one is created {@code rwx------} and must then be exactly that, owned by {@code uid}.
     * </ul>
     * Without POSIX attributes or a user ID nothing can be checked, so the destination is refused.
     * The checks are by path: a swap between a check and the use stays possible for the same OS user.
     * A refused rule is a {@link UsageException}, an I/O failure an {@link ExportException} with no
     * cause; both name the option and the rule, never the path.
     */
    static ExportFiles open(String option, Path dir, OptionalLong uid) {
        if (!POSIX) {
            throw new UsageException(option + ": the file system has no POSIX owner and permissions");
        }
        if (uid.isEmpty()) {
            throw new UsageException(option + ": the user ID of this process is unavailable");
        }
        Path target = dir.toAbsolutePath();
        Path name = target.getFileName();
        Path parent = target.getParent();
        if (name == null || parent == null || name.toString().equals(".") || name.toString().equals("..")) {
            throw new UsageException(option + " must name a folder in an existing parent folder");
        }
        try {
            PosixFileAttributes up;
            try {
                up = Files.readAttributes(parent, PosixFileAttributes.class);
            } catch (NoSuchFileException e) {
                throw new UsageException(option + ": its parent folder is missing or not a directory");
            }
            if (!up.isDirectory()) {
                throw new UsageException(option + ": its parent folder is missing or not a directory");
            }
            if (!owned(option, parent, uid.getAsLong())) {
                throw new UsageException(option
                        + ": its parent folder is not owned by the user this process runs as");
            }
            if (up.permissions().contains(PosixFilePermission.GROUP_WRITE)
                    || up.permissions().contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new UsageException(option + ": its parent folder is writable by group or others");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                PosixFileAttributes own = Files.readAttributes(target, PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (own.isSymbolicLink()) {
                    throw new UsageException(option + " is a symbolic link");
                }
                if (!own.isDirectory()) {
                    throw new UsageException(option + " is not a directory");
                }
                if (!owned(option, target, uid.getAsLong(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new UsageException(option + " is not owned by the user this process runs as");
                }
                if (!Collections.disjoint(own.permissions(), GROUP_OR_OTHER)) {
                    throw new UsageException(option + " has group or other permissions; it is not changed");
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(target)) {
                    if (entries.iterator().hasNext()) {
                        throw new UsageException(option + " is not empty");
                    }
                }
            } else {
                Files.createDirectory(target, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
                PosixFileAttributes made = Files.readAttributes(target, PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (made.isSymbolicLink() || !made.isDirectory() || !made.permissions().equals(OWNER_ONLY)
                        || !owned(option, target, uid.getAsLong(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new UsageException(option + " was not created as an owner-only (0700) folder");
                }
            }
        } catch (IOException e) {
            throw new ExportException(option + ": the destination cannot be prepared");
        }
        return new ExportFiles(target);
    }

    /** The numeric owner, as the path's own file system reports it. */
    private static boolean owned(String option, Path path, long uid, LinkOption... options) throws IOException {
        Object owner;
        try {
            owner = Files.getAttribute(path, "unix:uid", options);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new UsageException(option + ": the file system does not report a numeric owner");
        }
        return owner instanceof Integer i && Integer.toUnsignedLong(i) == uid;
    }

    /** Fixed-length records, each followed by a line feed, as the reference side writes them. */
    void records(String name, List<byte[]> records) {
        write(name, out -> {
            for (byte[] r : records) {
                out.write(r);
                out.write('\n');
            }
        });
    }

    void bytes(String name, byte[] content) {
        write(name, out -> out.write(content));
    }

    /** Removes every file this export wrote. */
    void discard() {
        for (Path p : written) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                // Best effort: the caller reports the original failure.
            }
        }
        written.clear();
    }

    private interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    private void write(String name, Content content) {
        Path target = dir.resolve(name);
        Path temp = dir.resolve("." + name + ".tmp");
        boolean created = false;
        try {
            Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            FileChannel channel = POSIX
                    ? FileChannel.open(temp, options, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")))
                    : FileChannel.open(temp, options);
            created = true;
            try (OutputStream out = Channels.newOutputStream(channel)) {
                content.writeTo(out);
                channel.force(true);
            }
            Files.createLink(target, temp);
            written.add(target);
        } catch (FileAlreadyExistsException e) {
            discard();
            throw new ExportException((created ? name : "the temporary name of " + name)
                    + " exists already in the export folder; nothing is replaced", e);
        } catch (IOException e) {
            discard();
            throw new ExportException("cannot write " + name + " in the export folder", e);
        } finally {
            if (created) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // The export's own failure, if any, is reported.
                }
            }
        }
    }
}
