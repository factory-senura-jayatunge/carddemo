package org.carddemo.batch.categorylisting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.batch.execution.OutputPublication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListingPublicationTest {
    @TempDir Path var;
    Path root;
    ListingPublication publication;

    @BeforeEach
    void paths() {
        root = OutputRoot.ensure(var, new OutputRoot.Instance("catalogue-test", UUID.randomUUID()));
        publication = new ListingPublication(root, 7);
    }

    String write(long execution, String text) throws Exception {
        try (var out = publication.create(execution)) { out.write(text.getBytes(StandardCharsets.ISO_8859_1)); }
        return publication.hashWork(publication.workName(execution));
    }

    Path target() { return publication.directory().resolve("PRTCATBL.txt"); }

    static void publish(ListingPublication paths, String work, String hash) {
        OutputPublication.publishFile(paths.work(work), paths.target(), hash);
    }

    @Test
    void publishesWithOwnerOnlyPermissionsAndNeverReplaces() throws Exception {
        String hash = write(1, "first");
        publish(publication, publication.workName(1), hash);
        assertThat(Files.readString(target())).isEqualTo("first");
        assertThat(Files.getPosixFilePermissions(target()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(publication.directory()))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        String other = write(2, "different");
        assertThatThrownBy(() -> publish(publication, publication.workName(2), other))
                .hasMessageContaining("PUBLICATION_MISMATCH");
        assertThat(Files.readString(target())).isEqualTo("first");
    }

    @Test
    void restartCanPublishTheCommittedOlderExecutionsBytes() throws Exception {
        String hash = write(1, "captured");
        var restarted = new ListingPublication(root, 7);
        publish(restarted, publication.workName(1), hash);
        assertThat(Files.readString(target())).isEqualTo("captured");
        // The work file has been removed; a restart verifies only the target.
        publish(restarted, publication.workName(1), hash);
        assertThat(Files.exists(publication.directory().resolve(publication.workName(1)))).isFalse();
    }

    @Test
    void zeroExecutionIdCanBePublishedAndRecovered() throws Exception {
        String workName = publication.workName(0);
        assertThat(workName).isEqualTo("PRTCATBL.txt.0.tmp");
        String hash = write(0, "captured zero");
        assertThat(publication.work(workName)).isEqualTo(publication.directory().resolve(workName));

        publish(publication, workName, hash);
        publish(new ListingPublication(root, 7), workName, hash);

        assertThat(Files.readString(target())).isEqualTo("captured zero");
        assertThat(Files.exists(publication.directory().resolve(workName))).isFalse();
    }

    @Test
    void refusesMissingOrChangedCommittedWork() throws Exception {
        String hash = write(1, "original");
        Files.writeString(publication.directory().resolve(publication.workName(1)), "changed");
        assertThatThrownBy(() -> publish(publication, publication.workName(1), hash))
                .hasMessageContaining("PUBLICATION_MISMATCH");
        assertThat(publication.targetExists()).isFalse();
        Files.delete(publication.directory().resolve(publication.workName(1)));
        assertThatThrownBy(() -> publish(publication, publication.workName(1), hash))
                .hasMessageContaining("PUBLICATION_MISMATCH");
    }

    @Test
    void refusesAnotherExecutionsWorkingPathAndNeverTruncatesAnExistingOne() throws Exception {
        write(1, "original");
        assertThatThrownBy(() -> publication.create(1)).hasMessageContaining("PUBLICATION_MISMATCH");
        assertThatThrownBy(() -> publication.work("PRTCATBL.txt.01.tmp"))
                .hasMessage("PUBLICATION_MISMATCH: PRTCATBL");
        assertThatThrownBy(() -> publication.work("private-name-sentinel"))
                .hasMessage("PUBLICATION_MISMATCH: PRTCATBL");
        assertThatThrownBy(() -> publish(publication, "../../unowned", "0".repeat(64)))
                .hasMessageContaining("PUBLICATION_MISMATCH");
    }

    @Test
    void publishesEmptyListing() throws Exception {
        String hash = write(1, "");
        publish(publication, publication.workName(1), hash);
        assertThat(Files.size(target())).isZero();
    }

    @Test
    void restartAfterInterruptionBetweenLinkAndDeleteRemovesOnlyTheIntentsWork() throws Exception {
        String hash = write(1, "captured");
        write(2, "unrelated execution");
        Path ownWork = publication.directory().resolve(publication.workName(1));
        // Exact on-disk prefix of a kill after createLink and before delete.
        Files.createLink(target(), ownWork);
        assertThat(Files.exists(ownWork)).isTrue();
        publish(new ListingPublication(root, 7), publication.workName(1), hash);
        assertThat(Files.exists(ownWork)).isFalse();
        assertThat(Files.readString(target())).isEqualTo("captured");
        assertThat(Files.readString(publication.directory().resolve(publication.workName(2))))
                .isEqualTo("unrelated execution");
    }

    @Test
    void missingWorkingFolderFailsWithoutRecreatingAnyPath() throws Exception {
        var missing = new ListingPublication(root, 99);
        Path folder = root.resolve("reports/prtcatbl/99");
        assertThat(Files.exists(root.resolve("reports"))).isFalse();
        assertThatThrownBy(() -> publish(missing, missing.workName(1), "0".repeat(64)))
                .hasMessageContaining("PUBLICATION_MISMATCH");
        assertThat(Files.exists(root.resolve("reports"))).isFalse();
        assertThat(Files.exists(folder)).isFalse();
    }

    @Test
    void refusesSymlinkWorkAndLeavesItsTargetAlone() throws Exception {
        Path outside = Files.writeString(var.resolve("outside"), "sentinel");
        Files.createSymbolicLink(publication.directory().resolve(publication.workName(1)), outside);
        assertThatThrownBy(() -> publish(publication, publication.workName(1), "0".repeat(64)))
                .hasMessageContaining("OUTPUT_PERMISSIONS");
        assertThat(Files.readString(outside)).isEqualTo("sentinel");
    }
}
