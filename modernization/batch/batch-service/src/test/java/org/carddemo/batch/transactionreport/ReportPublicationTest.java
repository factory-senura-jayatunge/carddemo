package org.carddemo.batch.transactionreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.platform.outputroot.OutputRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportPublicationTest {
    @TempDir Path var;
    Path root;
    ReportPublication publication;

    @BeforeEach
    void paths() {
        root = OutputRoot.ensure(var, new OutputRoot.Instance("tranrept-test", UUID.randomUUID()));
        publication = new ReportPublication(root, 42);
    }

    String write(long execution, String text) throws Exception {
        try (var out = publication.create(execution)) {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        }
        return publication.hashWork(publication.workName(execution));
    }

    @Test
    void theTargetIsWhereTransactionServiceLooksForIt() {
        assertThat(ReportPublication.relativePath(42)).isEqualTo("reports/tranrept/42/TRANREPT.txt");
        assertThat(publication.target()).isEqualTo(root.resolve(ReportPublication.relativePath(42)));
        assertThat(publication.workName(9)).isEqualTo("TRANREPT.txt.9.tmp");
        assertThat(ReportPublication.WORK).isEqualTo("carddemo.tranrept.workPath");
        assertThat(ReportPublication.HASH).isEqualTo("carddemo.tranrept.sha256");
    }

    @Test
    void publishesOwnerOnlyAndNeverReplaces() throws Exception {
        String hash = write(1, "first");
        OutputPublication.publishFile(publication.work(publication.workName(1)), publication.target(), hash);
        assertThat(Files.readString(publication.target())).isEqualTo("first");
        assertThat(Files.getPosixFilePermissions(publication.target()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(publication.targetExists()).isTrue();
        String other = write(2, "different");
        assertThatThrownBy(() -> OutputPublication.publishFile(publication.work(publication.workName(2)),
                publication.target(), other)).hasMessageContaining("PUBLICATION_MISMATCH");
        assertThat(Files.readString(publication.target())).isEqualTo("first");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../x/TRANREPT.txt.1.tmp", "TRANREPT.txt", "TRANREPT.txt.0.tmp",
        "PRTCATBL.txt.1.tmp", "TRANREPT.txt.1.tmp/..", ""})
    void refusesAnyWorkNameButThisOutputs(String name) {
        assertThatThrownBy(() -> publication.work(name)).hasMessageContaining("PUBLICATION_MISMATCH");
    }

    @Test
    void lookingForTheTargetCreatesNothing() {
        assertThat(publication.targetExists()).isFalse();
        assertThat(publication.folder()).doesNotExist();
        assertThat(root.resolve("reports")).doesNotExist();
    }

    @Test
    void eachRunHasItsOwnFolder() throws Exception {
        write(1, "run 42");
        var other = new ReportPublication(root, 43);
        assertThat(other.folder()).isNotEqualTo(publication.folder());
        assertThat(other.targetExists()).isFalse();
        assertThat(publication.folder().resolve(publication.workName(1))).exists();
    }
}
