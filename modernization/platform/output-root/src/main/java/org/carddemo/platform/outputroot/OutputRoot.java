package org.carddemo.platform.outputroot;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_PERMISSIONS;
import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_ROOT_INCOMPLETE;
import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_ROOT_INVALID;
import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_ROOT_LEGACY_SETTING;
import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_ROOT_MISMATCH;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The instance root {@code <CARDDEMO_VAR_DIR>/out/<sha256 of DB_NAME()>/<instance id>/} that every
 * batch output lives in (batch-execution-contract.md section 4.10). Final output names are unique
 * only within one database instance, so the root is derived from the database name and the
 * database-generated {@code ops.database_instance} marker, never from a setting of its own, and is
 * read again for each use instead of being cached.
 */
public final class OutputRoot {

    /** One database instance: {@code DB_NAME()} and {@code ops.database_instance.instance_id}. */
    public record Instance(String database, UUID id) {

        public Instance {
            if (id == null) {
                throw new OutputRootException(OUTPUT_ROOT_INVALID, "no instance ID");
            }
            checkDatabaseName(database);
        }

        /** {@code instanceIdText} in either case, as SQL Server renders it in upper case. */
        public static Instance of(String databaseName, String instanceIdText) {
            if (instanceIdText == null || !UUID_TEXT.matcher(instanceIdText).matches()) {
                throw new OutputRootException(OUTPUT_ROOT_INVALID, "the instance ID is not a UUID");
            }
            return new Instance(databaseName, UUID.fromString(instanceIdText));
        }

        /** The canonical text: lower case, 36 characters, the same in the path and the marker. */
        public String instanceText() {
            return id.toString();
        }

        /** The path component of the database: the lower-case hex SHA-256 of its UTF-8 bytes. */
        public String component() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8()));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is part of every JDK", e);
            }
        }

        /** The exact bytes of {@code .instance}. */
        public byte[] marker() {
            return (MARKER_HEADER + "\ndatabase=" + Base64.getUrlEncoder().withoutPadding().encodeToString(utf8())
                    + "\ninstance=" + instanceText() + "\n").getBytes(StandardCharsets.US_ASCII);
        }

        private byte[] utf8() {
            return database.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** An entry that db-migrate lists as kept and no longer read. */
    public record Kept(Kind kind, Path path, long files) {

        public enum Kind {
            /** Another instance's root under this database's component. */
            ROOT,
            /** A leftover {@code .<id>.<random>.tmp} sibling of an interrupted {@code ensure}. */
            SIBLING,
            /** Anything else under this database's component. */
            OTHER,
            /** An output folder of the layout before instance roots, directly under CARDDEMO_VAR_DIR. */
            PRE_INSTANCE
        }
    }

    public static final String VAR_DIR_VARIABLE = "CARDDEMO_VAR_DIR";
    public static final String VAR_DIR_PROPERTY = "carddemo.var-dir";
    /** Removed output settings, refused by name when still set. */
    public static final List<String> LEGACY_SETTINGS = List.of(
            "CARDDEMO_OUTPUT_DIR", "CARDDEMO_REPORTS_DIR", "CARDDEMO_TRANREPT_DIR", "CARDDEMO_INBOUND_DIR",
            "carddemo.batch.files.output-dir", "carddemo.batch.files.inbound-dir", "carddemo.transaction.report.root");

    static final String READ_SQL = "SELECT DB_NAME() AS database_name, CONVERT(CHAR(36), instance_id) AS instance_id"
            + " FROM ops.database_instance WHERE id = 1";
    static final String OUT = "out";
    static final String MARKER_FILE = ".instance";
    static final String MARKER_HEADER = "carddemo-output-root 1";
    static final List<String> PRE_INSTANCE = List.of("rejects", "reports", "statements", "exports", "dailytran/archive");

    private static final System.Logger LOG = System.getLogger(OutputRoot.class.getName());
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SIBLING = Pattern.compile(
            "\\.[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.tmp");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int SYSNAME_MAX = 128;
    private static final int MARKER_MAX = 1024;
    private static final Set<PosixFilePermission> OTHERS = EnumSet.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    private OutputRoot() {
    }

    /**
     * The start check, and the only one: a removed setting, or a missing or relative
     * CARDDEMO_VAR_DIR, fails the start. A missing root never does. {@code settings} maps a variable
     * or property name to its value, or to null.
     */
    public static Path varDir(Function<String, String> settings) {
        Objects.requireNonNull(settings, "settings");
        for (String name : LEGACY_SETTINGS) {
            String value = settings.apply(name);
            if (value != null && !value.isBlank()) {
                // The name only: the value is the operator's and stays out of messages and logs.
                throw new OutputRootException(OUTPUT_ROOT_LEGACY_SETTING, name + " is no longer read; remove it"
                        + " (outputs are under CARDDEMO_VAR_DIR/out/)");
            }
        }
        String value = settings.apply(VAR_DIR_PROPERTY);
        if (value == null || value.isBlank()) {
            value = settings.apply(VAR_DIR_VARIABLE);
        }
        if (value == null || value.isBlank()) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "CARDDEMO_VAR_DIR is unset");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "CARDDEMO_VAR_DIR is not a path: " + value, e);
        }
        if (!path.isAbsolute()) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "CARDDEMO_VAR_DIR is not absolute: " + value);
        }
        Path canonical = canonical(path.normalize());
        if (Files.exists(canonical) && !Files.isDirectory(canonical)) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "CARDDEMO_VAR_DIR is not a directory: " + canonical);
        }
        return canonical;
    }

    /** The marker row, on the caller's connection, which stays open. */
    public static Instance read(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(READ_SQL);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new OutputRootException(OUTPUT_ROOT_INVALID, "ops.database_instance has no row 1");
            }
            Instance instance = Instance.of(rows.getString(1), rows.getString(2));
            if (rows.next()) {
                throw new OutputRootException(OUTPUT_ROOT_INVALID, "ops.database_instance has more than one row 1");
            }
            return instance;
        }
    }

    /** {@code <varDir>/out/<component>/<instance id>}; nothing is read or touched. */
    public static Path root(Path varDir, Instance instance) {
        return requireAbsolute(varDir).resolve(OUT).resolve(instance.component()).resolve(instance.instanceText());
    }

    /**
     * The root when it exists and is valid; empty when {@code out/}, the database's component or the
     * root is missing. Creates and changes nothing.
     */
    public static Optional<Path> resolve(Path varDir, Instance instance) {
        Path root = root(varDir, instance);
        for (Path dir : List.of(root.getParent().getParent(), root.getParent(), root)) {
            if (!Files.exists(dir, NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            checkManaged(dir, true);
        }
        checkMarker(root, instance);
        return Optional.of(root);
    }

    /**
     * Creates the root with its marker, never exposing one without it, or validates the existing one.
     * A racing {@code ensure} for the same instance gets the same root.
     */
    public static Path ensure(Path varDir, Instance instance) {
        Path root = root(varDir, instance);
        Path component = root.getParent();
        try {
            Files.createDirectories(varDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        managedDirectory(component.getParent());
        managedDirectory(component);
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            create(root, instance);
        }
        checkManaged(root, true);
        checkMarker(root, instance);
        return root;
    }

    /** A folder below a root: each missing segment is created ({@code 0700}) and each is checked. */
    public static Path ensureDirectory(Path root, String... segments) {
        checkSegments(segments);
        Path dir = requireAbsolute(root);
        checkManaged(dir, true);
        for (String segment : segments) {
            dir = dir.resolve(segment);
            managedDirectory(dir);
        }
        return dir;
    }

    /**
     * A private folder below a trusted base, for a service's own scratch space outside {@code out/}
     * (batch-execution-contract.md 4.10.2). The base must be an absolute, existing directory and is
     * used as given: its owner and mode are never read or changed, and it is never created, so it
     * may be a {@code 0755} CARDDEMO_VAR_DIR. Each missing level is created with {@code 0700} given
     * at creation, and each level, new or existing, passes the managed-path check. A failed check
     * leaves that level as it is and creates nothing below it. Nothing is renamed or deleted.
     *
     * @return the last level
     * @throws OutputRootException {@code OUTPUT_ROOT_INVALID} for a null or relative base, a base that
     *         is missing or not a directory, no level, or a level that is not one name;
     *         {@code OUTPUT_PERMISSIONS} for a failed check
     * @throws UncheckedIOException for an I/O error while a level is created or read
     */
    public static Path privateDirectory(Path base, String... segments) {
        requireAbsolute(base);
        if (segments == null || segments.length == 0) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "no folder below the base " + base);
        }
        checkSegments(segments);
        if (!Files.isDirectory(base)) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, base + " is missing or not a directory");
        }
        // The base itself is not checked, so the file system is refused here, before a level is
        // created on it (RR4), as ensureDirectory's check of its root does.
        requirePosix(base);
        if (ProcessOwner.self().uid() != null && !base.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            throw new OutputRootException(OUTPUT_PERMISSIONS, base
                    + " is on a file system that does not report a numeric owner; its owner cannot be checked");
        }
        Path dir = base;
        for (String segment : segments) {
            dir = dir.resolve(segment);
            managedDirectory(dir);
        }
        return dir;
    }

    /**
     * A regular file below a root, every folder on the way checked; empty when one of them or the
     * file is missing. Creates and changes nothing.
     */
    public static Optional<Path> existingFile(Path root, String... segments) {
        checkSegments(segments);
        if (segments.length == 0) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "no file name");
        }
        Path path = requireAbsolute(root);
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        checkManaged(path, true);
        for (int i = 0; i < segments.length; i++) {
            path = path.resolve(segments[i]);
            if (!Files.exists(path, NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            checkManaged(path, i < segments.length - 1);
        }
        return Optional.of(path);
    }

    /**
     * Everything under the database's component except {@code current}'s root, and the pre-instance
     * output folders, each with its count of regular files. Reads only; follows no link.
     */
    public static List<Kept> kept(Path varDir, Instance current) {
        Path root = root(varDir, current);
        Path component = root.getParent();
        List<Kept> kept = new ArrayList<>();
        if (Files.isDirectory(component, NOFOLLOW_LINKS)) {
            try (Stream<Path> entries = Files.list(component)) {
                for (Path entry : entries.sorted().toList()) {
                    String name = entry.getFileName().toString();
                    if (name.equals(current.instanceText())) {
                        continue;
                    }
                    Kept.Kind kind = CANONICAL_UUID.matcher(name).matches() ? Kept.Kind.ROOT
                            : SIBLING.matcher(name).matches() ? Kept.Kind.SIBLING : Kept.Kind.OTHER;
                    kept.add(new Kept(kind, entry, countFiles(entry)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (String folder : PRE_INSTANCE) {
            Path path = varDir.resolve(folder);
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                kept.add(new Kept(Kept.Kind.PRE_INSTANCE, path, countFiles(path)));
            }
        }
        return List.copyOf(kept);
    }

    private static void create(Path root, Instance instance) {
        Path sibling = root.resolveSibling("." + instance.instanceText() + "." + UUID.randomUUID() + ".tmp");
        Path marker = sibling.resolve(MARKER_FILE);
        boolean moved = false;
        try {
            Files.createDirectory(sibling, permissions(sibling, "rwx------"));
            try (FileChannel channel = FileChannel.open(marker, Set.of(CREATE_NEW, WRITE), permissions(marker, "rw-------"))) {
                ByteBuffer bytes = ByteBuffer.wrap(instance.marker());
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            forceDirectory(sibling);
            moved = rename(sibling, root);
            if (moved) {
                forceDirectory(root.getParent());
                if (Files.exists(sibling, NOFOLLOW_LINKS)) {
                    throw new OutputRootException(OUTPUT_ROOT_INVALID, "the move to " + root + " left " + sibling);
                }
                checkManaged(root, true);
                checkMarker(root, instance);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (!moved) {
                removeOwnSibling(sibling, marker);
            }
        }
    }

    /**
     * Renames without replacing. {@code ATOMIC_MOVE} is {@code rename(2)}, which replaces an existing
     * target only when that target is an empty directory; a file or link target, or a directory with
     * any entry, makes it fail. Every root this class creates holds its marker from the moment it
     * appears, so a winner's root or an earlier root is never replaced. An empty directory made by
     * hand at the root's name is seen by the check before the move and reported as incomplete.
     * False when the root exists, or appeared meanwhile; a link at the root's name is refused.
     */
    static boolean rename(Path sibling, Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new OutputRootException(OUTPUT_PERMISSIONS, root + " is a symbolic link");
        }
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            Files.move(sibling, root, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            throw e;
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (FileSystemException e) {
            if (Files.exists(root, NOFOLLOW_LINKS)) {
                return false;
            }
            throw e;
        }
    }

    private static void removeOwnSibling(Path sibling, Path marker) {
        try {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(sibling);
        } catch (IOException e) {
            // No path: a path below CARDDEMO_VAR_DIR never reaches a log (contract 4.10, Messages
            // outside the root); kept() lists the sibling for the operator.
            LOG.log(System.Logger.Level.WARNING, "could not remove an unused sibling: {0}",
                    e.getClass().getSimpleName());
        }
    }

    private static void managedDirectory(Path dir) {
        if (!Files.exists(dir, NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(dir, permissions(dir, "rwx------"));
            } catch (FileAlreadyExistsException e) {
                // Another process created it; it is checked below like any existing one.
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        checkManaged(dir, true);
    }

    private static void checkMarker(Path root, Instance instance) {
        Path marker = root.resolve(MARKER_FILE);
        if (!Files.exists(marker, NOFOLLOW_LINKS)) {
            throw new OutputRootException(OUTPUT_ROOT_INCOMPLETE, root + " has no " + MARKER_FILE
                    + "; it is not repaired (see the database restore runbook)");
        }
        checkManaged(marker, false);
        byte[] expected = instance.marker();
        byte[] actual;
        try (SeekableByteChannel channel = Files.newByteChannel(marker, Set.of(READ, NOFOLLOW_LINKS))) {
            if (channel.size() > MARKER_MAX) {
                throw new OutputRootException(OUTPUT_ROOT_MISMATCH, marker + " is not a marker of this database");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read to the end.
            }
            actual = Arrays.copyOf(buffer.array(), buffer.position());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!Arrays.equals(expected, actual)) {
            throw new OutputRootException(OUTPUT_ROOT_MISMATCH, marker
                    + " names another database or instance; nothing under the root is used");
        }
    }

    /**
     * A managed path must be a directory (or, for a file, a regular file), not a symbolic link, owned
     * by the process user, with no group or other permission. Nothing is repaired: an existing path
     * is never changed, so a wider mode is refused, not narrowed. A file system that cannot report
     * owner and mode is refused too, since neither can then be checked.
     */
    private static void checkManaged(Path path, boolean directory) {
        requirePosix(path);
        PosixFileAttributes posix;
        try {
            posix = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            throw noPosix(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (posix.isSymbolicLink() || (directory ? !posix.isDirectory() : !posix.isRegularFile())) {
            throw new OutputRootException(OUTPUT_PERMISSIONS, path + " is a symbolic link or not a "
                    + (directory ? "directory" : "regular file"));
        }
        if (!ownedByThisProcess(path, posix.owner())) {
            throw new OutputRootException(OUTPUT_PERMISSIONS, path + " is owned by " + posix.owner().getName()
                    + ", not by the user this process runs as");
        }
        if (!Collections.disjoint(posix.permissions(), OTHERS)) {
            throw new OutputRootException(OUTPUT_PERMISSIONS, path + " has group or other permissions ("
                    + PosixFilePermissions.toString(posix.permissions()) + "); it is not changed");
        }
    }

    private static boolean ownedByThisProcess(Path path, UserPrincipal owner) {
        ProcessOwner self = ProcessOwner.self();
        if (self.uid() == null) {
            return owner.equals(self.principal());
        }
        // The process's uid can only be compared with a uid the path's own file system reports.
        if (!path.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            throw new OutputRootException(OUTPUT_PERMISSIONS, path
                    + " is on a file system that does not report a numeric owner; its owner cannot be checked");
        }
        try {
            return self.uid().equals(Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new OutputRootException(OUTPUT_PERMISSIONS, path
                    + " is on a file system that does not report a numeric owner; its owner cannot be checked");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void requirePosix(Path path) {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw noPosix(path);
        }
    }

    private static OutputRootException noPosix(Path path) {
        return new OutputRootException(OUTPUT_PERMISSIONS, path
                + " is on a file system without POSIX owner and permissions; they cannot be checked");
    }

    /**
     * The owner of a file this process has just created. user.name can differ from the effective
     * user (it comes from the environment or a launcher), so the owner check compares with what the
     * kernel records for this process's own files instead.
     */
    private record ProcessOwner(Object uid, UserPrincipal principal) {

        private static volatile ProcessOwner self;

        static ProcessOwner self() {
            ProcessOwner owner = self;
            if (owner == null) {
                owner = probe();
                self = owner;
            }
            return owner;
        }

        private static ProcessOwner probe() {
            try {
                Set<String> views = FileSystems.getDefault().supportedFileAttributeViews();
                Path probe = views.contains("posix")
                        ? Files.createTempFile("carddemo-owner-", ".tmp", permissions(PosixFilePermissions.fromString("rw-------")))
                        : Files.createTempFile("carddemo-owner-", ".tmp");
                try {
                    Object uid = views.contains("unix") ? Files.getAttribute(probe, "unix:uid", NOFOLLOW_LINKS) : null;
                    return new ProcessOwner(uid, Files.getOwner(probe, NOFOLLOW_LINKS));
                } finally {
                    Files.deleteIfExists(probe);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("could not create a file in java.io.tmpdir to learn this process's owner", e);
            }
        }
    }

    /** The base: its real path, or for a missing one the real path of its nearest existing ancestor. */
    private static Path canonical(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void checkDatabaseName(String name) {
        if (name == null || name.isEmpty() || name.length() > SYSNAME_MAX || name.indexOf('\0') >= 0) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "the database name is not a sysname of 1 to "
                    + SYSNAME_MAX + " characters");
        }
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(name));
        } catch (CharacterCodingException e) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "the database name is not well-formed Unicode", e);
        }
    }

    private static void checkSegments(String... segments) {
        for (String segment : segments) {
            if (segment == null || !SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                throw new OutputRootException(OUTPUT_ROOT_INVALID, "not a path segment: " + segment);
            }
        }
    }

    private static Path requireAbsolute(Path path) {
        if (path == null || !path.isAbsolute()) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "not an absolute path: " + path);
        }
        return path;
    }

    /** Refuses before anything is created where the new path's mode could not be checked afterwards. */
    private static FileAttribute<?>[] permissions(Path target, String mode) {
        requirePosix(target);
        return permissions(PosixFilePermissions.fromString(mode));
    }

    private static FileAttribute<?>[] permissions(Set<PosixFilePermission> mode) {
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(mode)};
    }

    /** Best effort: not every file system lets a directory be opened to force its entries to disk. */
    private static void forceDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // The marker itself is forced; the rename is atomic either way.
        }
    }

    private static long countFiles(Path start) {
        long[] count = {0};
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return count[0];
    }
}
