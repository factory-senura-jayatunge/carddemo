package org.carddemo.batch.execution;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.carddemo.batch.execution.OutputPublication.Outcome;
import org.carddemo.batch.execution.PublicationException.Reason;
import org.carddemo.batch.execution.PublicationFailures.PathReason;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Folder publication (batch-execution-contract.md section 4.8, revised 2026-11-12), for outputs
 * that are a flat set of files: statements and the export. The output is its files plus
 * {@value #MANIFEST}, one line {@code <sha256>  <name>} per file, sorted by name; the folder's
 * SHA-256 is that of its manifest, which is what an intent commits.
 *
 * <p>Nothing is renamed and nothing is replaced. The final folder is created with
 * {@code mkdir(2)} or joined when it exists; every name in it is made with {@code link(2)}, which
 * fails on an existing name. The publisher first links {@value #CLAIM} from its working manifest:
 * the first claim binds the folder to one manifest for good, so a publisher of any other manifest
 * fails before it links a file. The manifest is linked last, from {@value #CLAIM}, and marks the
 * folder complete. Every decision comes from the result of the operation itself; a working path
 * that another publisher of the same intent has already removed is not an error, the final name
 * then decides.
 *
 * <p>No exception and no log line of this class names an entry, a file or a path: each carries a
 * fixed reason code ({@link Reason}, or a path reason of {@code PublicationFailures}) and counts
 * only (section 4.8, Logs).
 */
public final class FolderPublication {

    /** The manifest, in the working folder and, last of all, in the final folder. */
    public static final String MANIFEST = "MANIFEST.sha256";
    /** The claim in the final folder: the same bytes as the manifest, linked before any file. */
    public static final String CLAIM = ".claim";

    /** A manifest larger than this is refused rather than read into memory. */
    static final int MANIFEST_MAX = 16 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(FolderPublication.class);
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private static final Pattern LINE = Pattern.compile("([0-9a-f]{64})  ([A-Za-z0-9_-][A-Za-z0-9._-]*)");
    private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** A name the path checks of {@link OutputRoot#existingFile} accept as one segment. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    /** The points between which a concurrent publisher or a crash can act; tests act there. */
    enum Step {
        STARTED, MANIFEST_READ, LISTED_ENTRY, FOLDER_READY, CLAIMED, FILE_OPENING, FILE_HASHED, FILE_LINKED,
        MANIFEST_LINKED, VERIFIED, WORK_REMOVED,
        /** A clean-up removed a working folder's entries and has not yet removed the folder. */
        CLEANUP_ENTRIES_REMOVED
    }

    /** Called at each {@link Step}, with the file name where the step has one. */
    @FunctionalInterface
    interface Hook {
        void at(Step step, String name);
    }

    private FolderPublication() {
    }

    /** The working folder's name under {@code <kind>/.work/}: {@code <id>.<jobExecutionId>}. */
    public static String workFolderName(String id, long jobExecutionId) {
        return id + "." + jobExecutionId;
    }

    /**
     * Creates the working folder {@code <workRoot>/<id>.<jobExecutionId>}, new and {@code 0700}
     * from the start. An existing one is not this execution's and is left in place.
     *
     * @throws PublicationException when the folder exists
     */
    public static Path createWorkFolder(Path workRoot, String id, long jobExecutionId) {
        return PublicationFailures.redacting(() -> {
            Path root = OutputRoot.ensureDirectory(workRoot);
            Path folder = root.resolve(workFolderName(id, jobExecutionId));
            try {
                return Files.createDirectory(folder,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (FileAlreadyExistsException e) {
                throw new PublicationException(Reason.WORK_EXISTS);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Writes the manifest of every file in a working folder, new, {@code 0600} and forced to disk,
     * and returns its SHA-256, the folder's. Call it once every file is written and closed, and
     * before the intent is committed; nothing in the folder is written again afterwards.
     *
     * @throws PublicationException when an entry's name cannot be published or the manifest exists
     */
    public static String writeManifest(Path workFolder) {
        return PublicationFailures.redacting(() -> manifestOf(workFolder));
    }

    private static String manifestOf(Path workFolder) {
        SortedMap<String, String> files = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(workFolder)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!FILE_NAME.matcher(name).matches() || name.equals(MANIFEST)) {
                    throw new PublicationException(Reason.WORK_ENTRY_UNNAMED);
                }
                files.put(name, OutputPublication.sha256(entry));
            }
        } catch (DirectoryIteratorException e) {
            throw new UncheckedIOException(e.getCause());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] manifest = manifest(files);
        try (OutputStream out = OutputPublication.createWorkFile(workFolder.resolve(MANIFEST))) {
            out.write(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return OutputPublication.sha256(manifest);
    }

    /** The manifest of files by name; names sort in byte order because they are ASCII. */
    static byte[] manifest(SortedMap<String, String> sha256ByName) {
        StringBuilder text = new StringBuilder();
        sha256ByName.forEach((name, sha256) -> text.append(sha256).append("  ").append(name).append('\n'));
        return text.toString().getBytes(US_ASCII);
    }

    /**
     * The files a manifest lists, in its order.
     *
     * @throws PublicationException when a line, a name or the order is not the manifest format
     */
    static Map<String, String> parse(byte[] manifest) {
        Map<String, String> files = new LinkedHashMap<>();
        String text = new String(manifest, US_ASCII);
        if (text.isEmpty()) {
            return files;
        }
        if (!text.endsWith("\n")) {
            throw new PublicationException(Reason.MANIFEST_NO_LINE_FEED);
        }
        String previous = null;
        int number = 0;
        for (String line : text.substring(0, text.length() - 1).split("\n", -1)) {
            number++;
            Matcher m = LINE.matcher(line);
            if (!m.matches() || m.group(2).equals(MANIFEST)) {
                throw new PublicationException(Reason.MANIFEST_LINE_FORMAT, number);
            }
            String name = m.group(2);
            if (previous != null && previous.compareTo(name) >= 0) {
                throw new PublicationException(Reason.MANIFEST_LINE_ORDER, number);
            }
            files.put(name, m.group(1));
            previous = name;
        }
        return files;
    }

    /**
     * Publishes a working folder onto {@code target} ({@code <kind>/<id>}) in the seven steps of
     * section 4.8, then removes the working folder. Two calls may publish one intent at once (a
     * restart and an execution that lost its lease), so any working path can vanish under either;
     * the final folder then decides.
     *
     * @return {@link Outcome#PUBLISHED} when this call linked the manifest, otherwise
     *         {@link Outcome#ALREADY_PUBLISHED}
     * @throws PublicationException {@code PUBLICATION_MISMATCH} when the working folder or the
     *         final folder holds other bytes or other names than the intent's manifest
     * @throws org.carddemo.platform.outputroot.OutputRootException {@code OUTPUT_PERMISSIONS} when
     *         a path fails the path checks
     */
    public static Outcome publishFolder(Path work, Path target, String sha256) {
        return publishFolder(work, target, sha256, (step, name) -> { });
    }

    static Outcome publishFolder(Path work, Path target, String sha256, Hook hook) {
        return PublicationFailures.redacting(() -> publish(work, target, sha256, hook));
    }

    private static Outcome publish(Path work, Path target, String sha256, Hook hook) {
        if (sha256 == null || !HEX_SHA256.matcher(sha256).matches()) {
            throw new PublicationException(Reason.NO_SHA256);
        }
        hook.at(Step.STARTED, null);
        Optional<byte[]> workManifest = readIfPresent(work, MANIFEST);
        byte[] manifest;
        if (workManifest.isPresent()) {
            manifest = workManifest.get();
            if (!OutputPublication.sha256(manifest).equals(sha256)) {
                throw new PublicationException(Reason.WORK_SHA256_MISMATCH);
            }
        } else {
            manifest = readIfPresent(target, CLAIM)
                    .orElseThrow(() -> new PublicationException(Reason.NOTHING_TO_PUBLISH));
            if (!OutputPublication.sha256(manifest).equals(sha256)) {
                throw new PublicationException(Reason.CLAIM_OTHER_OUTPUT);
            }
        }
        Map<String, String> files = parse(manifest);
        hook.at(Step.MANIFEST_READ, null);
        checkWorkEntries(work, files, hook);

        Path folder = OutputRoot.ensureDirectory(target.getParent(), target.getFileName().toString());
        refuseOtherNames(folder, files);
        hook.at(Step.FOLDER_READY, null);

        claim(work, folder, sha256, workManifest.isPresent());
        hook.at(Step.CLAIMED, null);

        for (Map.Entry<String, String> file : files.entrySet()) {
            linkFile(work, folder, file.getKey(), file.getValue(), hook);
        }

        Outcome outcome = linkManifest(folder, sha256);
        hook.at(Step.MANIFEST_LINKED, null);

        verify(folder, files, sha256);
        hook.at(Step.VERIFIED, null);

        removeWork(work, files, hook);
        return outcome;
    }

    /**
     * Step 1's listing: every entry besides the manifest must be a listed regular file. A folder
     * that is gone, or vanishes while it is listed, has no other entries, and an entry that
     * vanishes before its type is read is none: another publisher of the intent removed them.
     */
    private static void checkWorkEntries(Path work, Map<String, String> files, Hook hook) {
        DirectoryStream<Path> entries;
        try {
            entries = Files.newDirectoryStream(work);
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (entries) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                hook.at(Step.LISTED_ENTRY, name);
                if (name.equals(MANIFEST)) {
                    continue;
                }
                if (files.containsKey(name)) {
                    existingIfPresent(work, name);
                } else if (typeIfPresent(entry).isPresent()) {
                    throw new PublicationException(Reason.WORK_ENTRY_UNLISTED);
                }
            }
        } catch (DirectoryIteratorException e) {
            if (!(e.getCause() instanceof NoSuchFileException)) {
                throw new UncheckedIOException(e.getCause());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A shortcut before the claim: a final folder holding a name the manifest does not give is
     * refused before anything is linked into it. Step 6 still decides.
     */
    private static void refuseOtherNames(Path folder, Map<String, String> files) {
        Set<String> other = names(folder);
        other.removeAll(files.keySet());
        other.remove(MANIFEST);
        other.remove(CLAIM);
        if (!other.isEmpty()) {
            throw new PublicationException(Reason.FINAL_ENTRY_UNLISTED, other.size());
        }
    }

    /**
     * Step 3: {@value #CLAIM} is linked from the working manifest. When it exists already, or the
     * working manifest is gone, the {@value #CLAIM} there decides: the intent's bytes join, anything
     * else, or none, fails before a file is linked.
     */
    private static void claim(Path work, Path folder, String sha256, boolean fromWork) {
        if (fromWork) {
            try {
                Files.createLink(folder.resolve(CLAIM), work.resolve(MANIFEST));
                requireFinal(folder, CLAIM, sha256);
                return;
            } catch (FileAlreadyExistsException | NoSuchFileException e) {
                // Decided below by the .claim that is there.
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        byte[] claimed = readIfPresent(folder, CLAIM)
                .orElseThrow(() -> new PublicationException(Reason.CLAIM_MISSING));
        if (!OutputPublication.sha256(claimed).equals(sha256)) {
            throw new PublicationException(Reason.CLAIM_OTHER_OUTPUT);
        }
    }

    /** Step 4 for one file: hashed, then linked; a gone source or an existing name leaves it to the final name. */
    private static void linkFile(Path work, Path folder, String name, String sha256, Hook hook) {
        hook.at(Step.FILE_OPENING, name);
        Optional<String> hash = hashIfPresent(work, name);
        if (hash.isPresent()) {
            if (!hash.get().equals(sha256)) {
                throw new PublicationException(Reason.WORK_SHA256_MISMATCH);
            }
            hook.at(Step.FILE_HASHED, name);
            try {
                Files.createLink(folder.resolve(name), work.resolve(name));
                hook.at(Step.FILE_LINKED, name);
                return;
            } catch (FileAlreadyExistsException | NoSuchFileException e) {
                // Decided below by the final name.
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        requireFinal(folder, name, sha256);
        hook.at(Step.FILE_LINKED, name);
    }

    /** Step 5: the manifest, linked from {@value #CLAIM}, last. */
    private static Outcome linkManifest(Path folder, String sha256) {
        try {
            Files.createLink(folder.resolve(MANIFEST), folder.resolve(CLAIM));
            return Outcome.PUBLISHED;
        } catch (FileAlreadyExistsException | NoSuchFileException e) {
            requireFinal(folder, MANIFEST, sha256);
            return Outcome.ALREADY_PUBLISHED;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Step 6: exactly the listed files, the manifest and {@value #CLAIM}, each with its bytes. */
    private static void verify(Path folder, Map<String, String> files, String sha256) {
        Set<String> expected = new TreeSet<>(files.keySet());
        expected.add(MANIFEST);
        expected.add(CLAIM);
        Set<String> actual = names(folder);
        if (!actual.equals(expected)) {
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new TreeSet<>(actual);
            extra.removeAll(expected);
            throw new PublicationException(Reason.FINAL_NOT_EXACT, missing.size(), extra.size());
        }
        files.forEach((name, hash) -> requireFinal(folder, name, hash));
        requireFinal(folder, MANIFEST, sha256);
        requireFinal(folder, CLAIM, sha256);
    }

    /** Step 7: the listed working files, the working manifest and the folder; a gone one is no error. */
    private static void removeWork(Path work, Map<String, String> files, Hook hook) {
        try {
            for (String name : files.keySet()) {
                Files.deleteIfExists(work.resolve(name));
                hook.at(Step.WORK_REMOVED, name);
            }
            Files.deleteIfExists(work.resolve(MANIFEST));
            hook.at(Step.WORK_REMOVED, MANIFEST);
            Files.deleteIfExists(work);
        } catch (DirectoryNotEmptyException e) {
            // Entry names can be account IDs (a statement file is <acct_id>.pdf): only their count is logged.
            log.warn("WORK_FOLDER_KEPT: a working folder keeps {} entries that its manifest does not list; they are"
                    + " never published", names(work).size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Removes one working folder {@code <workRoot>/<name>} for a start's clean-up (section 4.8,
     * {@link OutputPublication#cleanUpFolders}): its entries, then the folder. No link is followed
     * and nothing is created. The folder and every entry pass the path checks of
     * {@link OutputRoot#existingFile} (a directory or a regular file, no symbolic link, owned by the
     * service user, no group or other bit) before the first removal, so a refused folder keeps
     * every entry. Removing an entry removes only that name: a published file that is another name
     * of a working file keeps its bytes and its inode.
     *
     *
     * <p>The checks and the removals go by path. Removing a name never follows it, but the folder's
     * own name is resolved again for each removal: a process with the service user's rights that
     * replaced the checked folder by a link between the check and a removal could make the clean-up
     * remove entries of the same names in the link's target. Only the service user can write under
     * its {@code 0700} root, so this residual is left to that boundary and is not handled here.
     *
     * @return true when the folder was there and this call removed it; false when it is missing, or
     *         when an entry appeared meanwhile (it is left for the next start, and logged)
     * @throws OutputRootException {@code OUTPUT_PERMISSIONS} when the folder or an entry fails the
     *         path checks or an entry's name is no path segment; nothing is removed then
     */
    static boolean removeWorkFolder(Path workRoot, String name) {
        return removeWorkFolder(workRoot, name, (step, file) -> { });
    }

    static boolean removeWorkFolder(Path workRoot, String name, Hook hook) {
        return PublicationFailures.redacting(() -> removeFolder(workRoot, name, hook));
    }

    private static boolean removeFolder(Path workRoot, String name, Hook hook) {
        Path folder = workRoot.resolve(name);
        if (!Files.exists(folder, NOFOLLOW_LINKS)) {
            return false;
        }
        // existingFile checks every folder on its way, stops at the first missing name and creates
        // nothing, so a probe below the folder checks the folder itself.
        OutputRoot.existingFile(workRoot, name, CLAIM);
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> list = Files.newDirectoryStream(folder)) {
            for (Path entry : list) {
                entries.add(entry.getFileName().toString());
            }
        } catch (NoSuchFileException e) {
            return false;
        } catch (DirectoryIteratorException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                return false;
            }
            throw new UncheckedIOException(e.getCause());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Path> files = new ArrayList<>();
        for (String entry : entries) {
            if (!SEGMENT.matcher(entry).matches() || entry.equals(".") || entry.equals("..")) {
                throw PublicationFailures.pathFailure(OutputRootException.Code.OUTPUT_PERMISSIONS,
                        PathReason.ENTRY_NOT_SEGMENT);
            }
            OutputRoot.existingFile(workRoot, name, entry).ifPresent(files::add);
        }
        try {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
            hook.at(Step.CLEANUP_ENTRIES_REMOVED, name);
            return Files.deleteIfExists(folder);
        } catch (DirectoryNotEmptyException e) {
            // Entry names can be account IDs (a statement file is <acct_id>.pdf): only their count is logged.
            log.warn("CLEANUP_ENTRIES_LATE: a working folder gained {} entries during its clean-up; they are never"
                    + " published and the next start removes them", names(folder).size());
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The final name must be a managed regular file with these bytes; it is kept as it is either way. */
    private static void requireFinal(Path folder, String name, String sha256) {
        Optional<String> hash = hashIfPresent(folder, name);
        if (hash.isEmpty()) {
            throw new PublicationException(Reason.FINAL_MISSING);
        }
        if (!hash.get().equals(sha256)) {
            throw new PublicationException(Reason.FINAL_SHA256_MISMATCH);
        }
    }

    /** The names in a folder; none when it is gone. */
    private static Set<String> names(Path folder) {
        Set<String> names = new TreeSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        } catch (NoSuchFileException e) {
            return names;
        } catch (DirectoryIteratorException e) {
            if (!(e.getCause() instanceof NoSuchFileException)) {
                throw new UncheckedIOException(e.getCause());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    /** The path checks of a managed regular file; empty when it or its folder is missing or vanishes. */
    private static Optional<Path> existingIfPresent(Path folder, String name) {
        try {
            return OutputRoot.existingFile(folder, name);
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static Optional<BasicFileAttributes> typeIfPresent(Path entry) {
        try {
            return Optional.of(Files.readAttributes(entry, BasicFileAttributes.class, NOFOLLOW_LINKS));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A managed file's bytes, or empty when it is missing or vanishes before it is read. */
    private static Optional<byte[]> readIfPresent(Path folder, String name) {
        Optional<Path> file = existingIfPresent(folder, name);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file.get(), Set.of(READ, NOFOLLOW_LINKS))) {
            if (channel.size() > MANIFEST_MAX) {
                throw new PublicationException(Reason.MANIFEST_TOO_LARGE);
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read to the end.
            }
            return Optional.of(Arrays.copyOf(buffer.array(), buffer.position()));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A managed file's SHA-256, or empty when it is missing or vanishes while it is read. */
    private static Optional<String> hashIfPresent(Path folder, String name) {
        Optional<Path> file = existingIfPresent(folder, name);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OutputPublication.sha256(file.get()));
        } catch (PublicationException e) {
            return Optional.empty();
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
