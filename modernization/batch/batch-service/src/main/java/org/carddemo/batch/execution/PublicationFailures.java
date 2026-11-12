package org.carddemo.batch.execution;

import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_PERMISSIONS;
import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_ROOT_INVALID;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.util.function.Supplier;

import org.carddemo.platform.outputroot.OutputRootException;

/**
 * Keeps names and paths out of what the publication and the clean-up throw (section 4.8, Logs).
 * {@link org.carddemo.platform.outputroot.OutputRoot}'s path checks and the file system name the
 * path in their messages, and a path holds entry names, which can be personal data. Every public
 * entry point of {@link OutputPublication} and {@link FolderPublication} lets its failures leave
 * through {@link #redacting}: an {@link OutputRootException} keeps its code and gets a fixed
 * {@link PathReason}, an {@link UncheckedIOException} or a {@link DirectoryIteratorException} keeps
 * the type of its cause, and none keeps a cause or a suppressed exception that could name a path.
 */
final class PublicationFailures {

    /** The closed set of reasons an {@link OutputRootException} of the publication carries. */
    enum PathReason {
        PATH_REFUSED("a managed path is a symbolic link, not a directory or a regular file, has another owner"
                + " or has a group or other bit; it is not changed"),
        PATH_INVALID("a path is not absolute or a name is no path segment"),
        ENTRY_NOT_SEGMENT("a working folder holds an entry whose name is no path segment; nothing in it is removed"),
        ROOT_REFUSED("the output root is refused");

        private final String text;

        PathReason(String text) {
            this.text = text;
        }
    }

    /** The prefix of an {@link UncheckedIOException}'s message; the cause's class name follows. */
    static final String IO = "OUTPUT_IO: ";

    private PublicationFailures() {
    }

    static <T> T redacting(Supplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            throw redacted(e);
        }
    }

    static RuntimeException redacted(RuntimeException e) {
        if (e instanceof OutputRootException o) {
            return redacted(o);
        }
        if (e instanceof UncheckedIOException u) {
            return redacted(u);
        }
        // A directory stream's iterator wraps the file system's exception, which names the folder.
        if (e instanceof DirectoryIteratorException d) {
            return redacted(new UncheckedIOException(d.getCause()));
        }
        return e;
    }

    static OutputRootException pathFailure(OutputRootException.Code code, PathReason reason) {
        return new OutputRootException(code, reason.name() + ": " + reason.text);
    }

    private static OutputRootException redacted(OutputRootException e) {
        if (e.getCause() == null && e.getSuppressed().length == 0) {
            for (PathReason reason : PathReason.values()) {
                if (e.getMessage().equals(pathFailure(e.code(), reason).getMessage())) {
                    return e;
                }
            }
        }
        PathReason reason = e.code() == OUTPUT_PERMISSIONS ? PathReason.PATH_REFUSED
                : e.code() == OUTPUT_ROOT_INVALID ? PathReason.PATH_INVALID : PathReason.ROOT_REFUSED;
        return pathFailure(e.code(), reason);
    }

    private static UncheckedIOException redacted(UncheckedIOException e) {
        IOException cause = e.getCause();
        if ((IO + cause.getClass().getSimpleName()).equals(e.getMessage()) && cause.getMessage() == null
                && cause.getCause() == null && cause.getSuppressed().length == 0 && e.getSuppressed().length == 0) {
            return e;
        }
        IOException redacted = redacted(cause);
        return new UncheckedIOException(IO + redacted.getClass().getSimpleName(), redacted);
    }

    /**
     * An exception of the same type, without message or cause: callers decide by the type
     * ({@link NoSuchFileException} means another publisher removed the path).
     */
    static IOException redacted(IOException e) {
        return switch (e) {
            case NoSuchFileException x -> new NoSuchFileException(null);
            case FileAlreadyExistsException x -> new FileAlreadyExistsException(null);
            case DirectoryNotEmptyException x -> new DirectoryNotEmptyException(null);
            case NotDirectoryException x -> new NotDirectoryException(null);
            case AccessDeniedException x -> new AccessDeniedException(null);
            case NotLinkException x -> new NotLinkException(null);
            case FileSystemLoopException x -> new FileSystemLoopException(null);
            case FileSystemException x -> new FileSystemException(null);
            default -> new IOException();
        };
    }
}
