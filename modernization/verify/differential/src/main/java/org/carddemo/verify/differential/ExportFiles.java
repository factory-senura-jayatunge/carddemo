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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The export folder. The records hold full card numbers, so the folder is owner-only (0700) and
 * each file owner-only (0600) where the file system supports POSIX permissions. Each file is
 * written under a new temporary name and, when complete, linked onto its own name, which must not
 * exist: nothing in the folder is ever replaced, and a temporary name that exists is not this
 * export's and is left alone. If any file fails, the files already written are removed, so a
 * folder never holds a partial export. The caller writes {@code counts.json} last.
 */
final class ExportFiles {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path dir;
    private final List<Path> written = new ArrayList<>();

    private ExportFiles(Path dir) {
        this.dir = dir;
    }

    /** The folder, created owner-only; an existing one must be an empty directory. */
    static ExportFiles open(Path dir) {
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UsageException("--export " + dir + " is not a directory");
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                    if (entries.iterator().hasNext()) {
                        throw new UsageException("--export " + dir + " is not empty");
                    }
                }
                if (POSIX) {
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                }
            } else {
                Path parent = dir.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (POSIX) {
                    Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(dir);
                }
            }
        } catch (IOException e) {
            throw new ExportException("cannot prepare the export folder " + dir, e);
        }
        return new ExportFiles(dir);
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
