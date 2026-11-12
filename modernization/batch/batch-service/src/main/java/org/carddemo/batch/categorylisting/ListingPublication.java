package org.carddemo.batch.categorylisting;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.platform.outputroot.OutputRoot;

/** PRTCATBL path layout; the shared file-publication API owns the publication protocol. */
final class ListingPublication {
    static final String PREFIX = "carddemo.prtcatbl";
    static final String WORK = PREFIX + OutputPublication.WORK_PATH;
    static final String HASH = PREFIX + OutputPublication.SHA256;
    static final String TARGET = "PRTCATBL.txt";
    private static final Pattern WORK_NAME = Pattern.compile("PRTCATBL\\.txt\\.(?:0|[1-9][0-9]*)\\.tmp");
    private final Path root;
    private final String instanceId;

    ListingPublication(Path root, long instanceId) {
        this.root = root;
        this.instanceId = Long.toString(instanceId);
    }

    Path directory() {
        return OutputRoot.ensureDirectory(root, "reports", "prtcatbl", instanceId);
    }

    Path folder() {
        return root.resolve("reports").resolve("prtcatbl").resolve(instanceId);
    }

    Path target() {
        return folder().resolve(TARGET);
    }

    String workName(long executionId) {
        return OutputPublication.workFileName(TARGET, executionId);
    }

    OutputStream create(long executionId) {
        return OutputPublication.createWorkFile(directory().resolve(workName(executionId)));
    }

    boolean targetExists() {
        return OutputRoot.existingFile(root, "reports", "prtcatbl", instanceId, TARGET).isPresent();
    }

    Path work(String work) {
        if (work == null || !WORK_NAME.matcher(work).matches()) mismatch();
        return folder().resolve(work);
    }

    String hashWork(String work) {
        return OutputPublication.sha256(work(work));
    }

    static String hash(Path path) {
        return OutputPublication.sha256(path);
    }

    static void mismatch() {
        throw new PublicationException("PRTCATBL");
    }
}
