package org.carddemo.transaction;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The private folder of the download's nameless snapshots (transaction-service.md 16, 3.5 and 4):
 * the setting {@value #PROPERTY}, or {@code <CARDDEMO_VAR_DIR>/private/transaction-service/}. The
 * value is parsed and its base resolved once, at start; every later use takes the canonical base
 * and the levels fixed here and never reads the raw value again. A wrong value fails the start
 * with the property's name only: the value, and every exception message that holds it, stays out
 * of messages and logs. Its levels are created and checked at each use by
 * {@link OutputRoot#privateDirectory}, never here.
 */
public final class ReportStaging {

    static final String PROPERTY = "carddemo.transaction.report.staging-dir";
    static final int CLEAN_UP_LIMIT = 10_000;
    static final String LIST_IO = "LIST_IO";

    private static final Logger log = LoggerFactory.getLogger(ReportStaging.class);
    private static final List<String> DEFAULT_LEVELS = List.of("private", "transaction-service");
    // The output-root module's segment rule, which OutputRoot keeps private; privateDirectory
    // checks the same rule again at each use.
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern OWN_NAME = Pattern.compile(
            "download-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp");

    private final Path base;
    private final List<String> levels;
    private final Hooks hooks;

    /** Test seams around the snapshot's open; production uses {@link Hooks#NONE}. */
    interface Hooks {
        Hooks NONE = new Hooks() {
        };

        /** The open options of a new snapshot. */
        default Set<OpenOption> snapshotOptions(Set<OpenOption> options) {
            return options;
        }

        /** Called right after the open and before the name check; may wrap the channel. */
        default FileChannel opened(Path name, FileChannel channel) throws IOException {
            return channel;
        }

        /** The clean-up's listing of the folder; may wrap it. */
        default DirectoryStream<Path> listed(DirectoryStream<Path> entries) {
            return entries;
        }
    }

    /**
     * What the start clean-up did: counts only, never a name or a path. {@code skipped} is the reason
     * code when the clean-up stopped early, with the counts taken until then; null when it ran through.
     */
    record CleanUp(String skipped, int deleted, int kept, int failed, boolean stopped) {
    }

    private ReportStaging(Path base, List<String> levels, Hooks hooks) {
        this.base = base;
        this.levels = List.copyOf(levels);
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    /**
     * The start resolution. {@code varDir} is CARDDEMO_VAR_DIR as {@link OutputRoot#varDir}
     * returned it; {@code settings} maps a property name to its value, or to null.
     *
     * @throws IllegalArgumentException with the property's name, never its value
     */
    static ReportStaging resolve(Path varDir, Function<String, String> settings) {
        return resolve(varDir, settings, Hooks.NONE);
    }

    static ReportStaging resolve(Path varDir, Function<String, String> settings, Hooks hooks) {
        Objects.requireNonNull(varDir, "varDir");
        String value = settings.apply(PROPERTY);
        ReportStaging staging = value == null ? new ReportStaging(varDir, DEFAULT_LEVELS, hooks) : override(value, hooks);
        staging.refuseAroundOut(varDir);
        return staging;
    }

    private static ReportStaging override(String value, Hooks hooks) {
        if (value.isEmpty()) {
            throw invalid("must not be empty");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            // Its message holds the input.
            throw invalid("is not a valid path");
        }
        if (!path.isAbsolute()) {
            throw invalid("must be an absolute path");
        }
        Path parent = path.getParent();
        Path name = path.getFileName();
        if (parent == null || name == null) {
            throw invalid("must name a folder below an existing folder");
        }
        if (!path.equals(path.normalize())) {
            throw invalid("must not contain . or .. elements");
        }
        String folder = name.toString();
        if (!SEGMENT.matcher(folder).matches() || folder.equals(".") || folder.equals("..")) {
            throw invalid("must end in a name of letters, digits, '.', '_' or '-'");
        }
        Path realParent;
        try {
            realParent = parent.toRealPath();
        } catch (IOException e) {
            // Its message holds the path.
            throw invalid("names a folder whose parent cannot be resolved");
        }
        if (!Files.isDirectory(realParent, NOFOLLOW_LINKS)) {
            throw invalid("names a folder whose parent is not a directory");
        }
        return new ReportStaging(realParent, List.of(folder), hooks);
    }

    /** C3: no staging folder in, at or around {@code <CARDDEMO_VAR_DIR>/out/}, on resolved paths too. */
    private void refuseAroundOut(Path varDir) {
        Path folder = expected();
        Set<Path> outs = new LinkedHashSet<>();
        outs.add(varDir.resolve("out"));
        try {
            if (Files.exists(varDir)) {
                outs.add(varDir.toRealPath().resolve("out"));
            }
            Path out = varDir.resolve("out");
            if (Files.exists(out)) {
                outs.add(out.toRealPath());
            }
        } catch (IOException e) {
            throw invalid("cannot be compared with CARDDEMO_VAR_DIR/out");
        }
        for (Path out : outs) {
            if (folder.startsWith(out) || out.startsWith(folder)) {
                throw invalid("must not be CARDDEMO_VAR_DIR/out, inside it, or contain it");
            }
        }
    }

    private static IllegalArgumentException invalid(String rule) {
        return new IllegalArgumentException(PROPERTY + " " + rule);
    }

    /** The folder as resolved at start, before any level is created or checked. */
    Path expected() {
        Path folder = base;
        for (String level : levels) {
            folder = folder.resolve(level);
        }
        return folder;
    }

    /**
     * The folder, its levels created ({@code 0700}) and checked.
     *
     * @throws OutputRootException {@code OUTPUT_PERMISSIONS} or {@code OUTPUT_ROOT_INVALID}
     * @throws UncheckedIOException for an I/O error on a level
     */
    Path folder() {
        return OutputRoot.privateDirectory(base, levels.toArray(String[]::new));
    }

    /**
     * A new nameless snapshot: created {@code 0600} in the checked folder, and its name removed
     * before the channel is returned. {@code DELETE_ON_CLOSE} is best effort, so a name still
     * there is deleted here; if that fails, the channel is closed and the download refused.
     *
     * @throws OutputRootException or UncheckedIOException from {@link #folder()}
     * @throws IOException when the snapshot cannot be created or its name removed
     */
    FileChannel open() throws IOException {
        Path folder = folder();
        Path name = folder.resolve("download-" + UUID.randomUUID() + ".tmp");
        Set<OpenOption> options = hooks.snapshotOptions(Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE, NOFOLLOW_LINKS));
        FileChannel channel = FileChannel.open(name, options,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            channel = hooks.opened(name, channel);
            if (Files.exists(name, NOFOLLOW_LINKS)) {
                try {
                    Files.delete(name);
                } catch (NoSuchFileException gone) {
                    // Removed in between: the outcome wanted.
                }
            }
            return channel;
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * The start clean-up of item 4: the configured folder is listed once, without following links,
     * and only regular files of the folder's owner named exactly {@code download-<uuid>.tmp} are
     * deleted. A failed check, or a folder that cannot be listed, deletes nothing; a read error
     * while the listing is read ends it with the counts taken so far. Nothing here fails the start.
     */
    CleanUp cleanUp() {
        Path folder = expected();
        if (!Files.exists(folder, NOFOLLOW_LINKS)) {
            return logged(new CleanUp("NO_FOLDER", 0, 0, 0, false));
        }
        try {
            folder();
        } catch (OutputRootException e) {
            return logged(new CleanUp(e.code().name(), 0, 0, 0, false));
        } catch (UncheckedIOException e) {
            return logged(new CleanUp("SNAPSHOT_IO", 0, 0, 0, false));
        }
        try (DirectoryStream<Path> entries = hooks.listed(Files.newDirectoryStream(folder))) {
            return logged(entries instanceof SecureDirectoryStream<Path> secure ? secure(secure) : plain(folder, entries));
        } catch (IOException | UncheckedIOException | DirectoryIteratorException | SecurityException e) {
            return logged(new CleanUp(LIST_IO, 0, 0, 0, false));
        }
    }

    private static CleanUp secure(SecureDirectoryStream<Path> stream) throws IOException {
        UserPrincipal owner = stream.getFileAttributeView(PosixFileAttributeView.class).readAttributes().owner();
        int deleted = 0;
        int kept = 0;
        int failed = 0;
        int seen = 0;
        try {
            for (Path entry : stream) {
                if (++seen > CLEAN_UP_LIMIT) {
                    return new CleanUp(null, deleted, kept, failed, true);
                }
                Path name = entry.getFileName();
                PosixFileAttributes attributes;
                try {
                    attributes = stream.getFileAttributeView(name, PosixFileAttributeView.class, NOFOLLOW_LINKS)
                            .readAttributes();
                } catch (IOException e) {
                    failed++;
                    continue;
                }
                if (!own(name, attributes, owner)) {
                    kept++;
                    continue;
                }
                try {
                    stream.deleteFile(name);
                    deleted++;
                } catch (IOException e) {
                    failed++;
                }
            }
        } catch (DirectoryIteratorException e) {
            // A read error of the listing itself: a RuntimeException whose cause can name the folder.
            return new CleanUp(LIST_IO, deleted, kept, failed, true);
        }
        return new CleanUp(null, deleted, kept, failed, false);
    }

    private static CleanUp plain(Path folder, DirectoryStream<Path> stream) throws IOException {
        UserPrincipal owner = Files.readAttributes(folder, PosixFileAttributes.class, NOFOLLOW_LINKS).owner();
        int deleted = 0;
        int kept = 0;
        int failed = 0;
        int seen = 0;
        try {
            for (Path entry : stream) {
                if (++seen > CLEAN_UP_LIMIT) {
                    return new CleanUp(null, deleted, kept, failed, true);
                }
                PosixFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(entry, PosixFileAttributes.class, NOFOLLOW_LINKS);
                } catch (IOException e) {
                    failed++;
                    continue;
                }
                if (!own(entry.getFileName(), attributes, owner)) {
                    kept++;
                    continue;
                }
                try {
                    Files.delete(entry);
                    deleted++;
                } catch (IOException e) {
                    failed++;
                }
            }
        } catch (DirectoryIteratorException e) {
            return new CleanUp(LIST_IO, deleted, kept, failed, true);
        }
        return new CleanUp(null, deleted, kept, failed, false);
    }

    private static boolean own(Path name, PosixFileAttributes attributes, UserPrincipal owner) {
        return attributes.isRegularFile() && !attributes.isSymbolicLink() && owner.equals(attributes.owner())
                && OWN_NAME.matcher(name.toString()).matches();
    }

    private static CleanUp logged(CleanUp result) {
        if (result.skipped() != null && result.deleted() + result.kept() + result.failed() == 0) {
            log.info("report staging clean-up: nothing deleted ({})", result.skipped());
        } else if (result.skipped() != null) {
            log.info("report staging clean-up: ended early ({}), deleted {}, kept {}, failed {}", result.skipped(),
                    result.deleted(), result.kept(), result.failed());
        } else {
            log.info("report staging clean-up: deleted {}, kept {}, failed {}, listing stopped {}", result.deleted(),
                    result.kept(), result.failed(), result.stopped());
        }
        return result;
    }
}
