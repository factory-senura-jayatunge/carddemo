package org.carddemo.batch.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The file in which the one-shot load names the dataLoadJob execution it started
 * ({@code carddemo.batch.load.execution-id-file}), so that a caller binds to that execution and
 * never to "the last load". Created empty, owner-only, before the batch window opens; the ID is
 * written when the execution starts, before its first step. An empty file means that no
 * execution was created or, with a non-zero exit code, that the load cannot tell: a caller treats
 * it as unknown and refuses to bind.
 */
final class ExecutionIdFile implements AutoCloseable {

    static final String PROPERTY = "carddemo.batch.load.execution-id-file";

    /** A setting the load refuses before it launches anything; the message never quotes the path. */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    private final FileChannel channel;

    private ExecutionIdFile(FileChannel channel) {
        this.channel = channel;
    }

    /** @return the created file, or {@code null} when the property is unset or blank */
    static ExecutionIdFile create(String configured) throws Refused {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path;
        try {
            path = Path.of(configured);
        } catch (InvalidPathException e) {
            throw new Refused(PROPERTY + " cannot be created (" + e.getClass().getSimpleName() + ")");
        }
        if (!path.isAbsolute()) {
            throw new Refused(PROPERTY + " must be an absolute path");
        }
        Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------"));
                return new ExecutionIdFile(FileChannel.open(path, options, ownerOnly));
            }
            return new ExecutionIdFile(FileChannel.open(path, options));
        } catch (FileAlreadyExistsException e) {
            throw new Refused(PROPERTY + " already exists");
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new Refused(PROPERTY + " cannot be created (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Writes the ID in decimal ASCII and one LF, and forces it to disk. */
    void write(long executionId) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap((executionId + "\n").getBytes(StandardCharsets.US_ASCII));
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
