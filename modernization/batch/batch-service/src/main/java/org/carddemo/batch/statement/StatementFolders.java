package org.carddemo.batch.statement;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.platform.outputroot.OutputRoot;

/**
 * The statement folders under the instance root (batch.md, "Folders"): the final folder
 * {@code statements/<cycleId>-<attempt>/} and each execution's working folder
 * {@code statements/.work/<cycleId>-<attempt>.<jobExecutionId>/}, and the check of a folder against
 * the file set its run expects (publication steps 1 and 3).
 */
final class StatementFolders {

    static final String STATEMENTS = "statements";
    static final String WORK = ".work";
    static final String TEXT = "STATEMNT.txt";
    static final String HTML = "STATEMNT.html";
    static final String INDEX = "index.csv";

    private StatementFolders() {
    }

    /** {@code statements/}, created {@code 0700} when missing, and checked (contract section 4.10). */
    static Path statements(Path root) {
        return OutputRoot.ensureDirectory(root, STATEMENTS);
    }

    /** {@code statements/.work/}, created {@code 0700} when missing, and checked. */
    static Path workRoot(Path root) {
        return OutputRoot.ensureDirectory(root, STATEMENTS, WORK);
    }

    /**
     * The files a run publishes besides its manifest, by name, with the SHA-256 its account row
     * holds, or null where only the manifest gives one: the two legacy files, or each account row's
     * file and {@value #INDEX}.
     */
    static SortedMap<String, String> expected(StatementRuns.Run run, StatementRuns runs) {
        SortedMap<String, String> files = new TreeMap<>();
        if (run.mode() == StatementRuns.Mode.LEGACY) {
            files.put(TEXT, null);
            files.put(HTML, null);
        } else {
            files.putAll(runs.accountFiles(run.id()));
            files.put(INDEX, null);
        }
        return files;
    }

    /**
     * Reads every file of {@code folder} back from disk and hashes it. The folder must hold exactly
     * the expected files, the manifest and {@code extras}; each file must have the SHA-256 its
     * account row holds, where it has one; and the manifest must be, byte for byte, the manifest of
     * the hashes read.
     *
     * <p>A failure's detail holds counts only: a statement file's name is an account ID, and no
     * path or entry name goes into an exit description or a log.
     *
     * @return the manifest's SHA-256, the folder's
     * @throws PublicationException {@code PUBLICATION_MISMATCH} otherwise
     */
    static String verify(Path folder, SortedMap<String, String> expected, Set<String> extras) {
        Set<String> names = new TreeSet<>(expected.keySet());
        names.add(FolderPublication.MANIFEST);
        names.addAll(extras);
        Set<String> actual = list(folder);
        if (!actual.equals(names)) {
            Set<String> missing = new TreeSet<>(names);
            missing.removeAll(actual);
            Set<String> other = new TreeSet<>(actual);
            other.removeAll(names);
            throw new PublicationException("the folder does not hold exactly the run's files (missing "
                    + missing.size() + ", other " + other.size() + ")");
        }
        SortedMap<String, String> hashes = new TreeMap<>();
        int differing = 0;
        for (Map.Entry<String, String> file : expected.entrySet()) {
            String hash = OutputPublication.sha256(folder.resolve(file.getKey()));
            if (file.getValue() != null && !file.getValue().equals(hash)) {
                differing++;
            }
            hashes.put(file.getKey(), hash);
        }
        if (differing > 0) {
            throw new PublicationException(differing + " of the folder's statement files do not have the SHA-256"
                    + " their account rows hold");
        }
        if (!java.util.Arrays.equals(read(folder, FolderPublication.MANIFEST), manifest(hashes))) {
            throw new PublicationException("the folder's manifest is not the manifest of the files beside it");
        }
        return OutputPublication.sha256(folder.resolve(FolderPublication.MANIFEST));
    }

    /** One line {@code <sha256>  <name>} per file, sorted by name in byte order (contract section 4.8). */
    static byte[] manifest(SortedMap<String, String> sha256ByName) {
        StringBuilder text = new StringBuilder();
        sha256ByName.forEach((name, sha256) -> text.append(sha256).append("  ").append(name).append('\n'));
        return text.toString().getBytes(US_ASCII);
    }

    private static Set<String> list(Path folder) {
        Set<String> names = new TreeSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        } catch (NoSuchFileException e) {
            throw new PublicationException("the folder is missing");
        } catch (DirectoryIteratorException e) {
            throw new UncheckedIOException(e.getCause());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    private static byte[] read(Path folder, String name) {
        Path file = OutputRoot.existingFile(folder, name)
                .orElseThrow(() -> new PublicationException("the folder's " + name + " is missing"));
        try (InputStream in = Files.newInputStream(file, NOFOLLOW_LINKS)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
