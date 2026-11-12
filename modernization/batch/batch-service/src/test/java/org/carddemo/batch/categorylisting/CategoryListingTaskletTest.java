package org.carddemo.batch.categorylisting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.catalogue.CatalogueFailures;
import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.LeaseKeeper;
import org.carddemo.batch.execution.LeaseLostException;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.outputroot.OutputRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CategoryListingTaskletTest {
    @TempDir Path var;
    Path root;
    ListingPublication publication;
    JdbcClient db;
    JobRepository repository;
    ExecutionLeases leases;
    LeaseKeeper keeper;
    OutputPublication outputs;
    JobExecution job;
    CategoryListingTasklet tasklet;

    @BeforeEach
    void fixture() {
        root = OutputRoot.ensure(var, new OutputRoot.Instance("catalogue-test", UUID.randomUUID()));
        publication = new ListingPublication(root, 7);
        db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        repository = mock(JobRepository.class);
        leases = mock(ExecutionLeases.class);
        keeper = new LeaseKeeper(leases);
        outputs = new OutputPublication(repository, leases, keeper);
        job = new JobExecution(1L, new JobInstance(7L, "categoryBalanceListingJob"), new JobParameters());
        job.setStatus(BatchStatus.STARTED);
        when(repository.getJobExecutions(job.getJobInstance())).thenReturn(List.of(job));
        when(leases.renew(1L)).thenReturn(true);
        tasklet = new CategoryListingTasklet(db, repository, outputs, ignored -> root,
                FidelitySwitches.allLegacy(BatchSwitches.ALL));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransactions() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @SuppressWarnings("unchecked")
    void input(byte[] bytes) {
        var query = db.sql(anyString()).query(any(RowMapper.class));
        doReturn(List.of(bytes)).when(query).list();
    }

    RepeatStatus execute() throws Exception {
        StepExecution step = new StepExecution(1L, "list", job);
        return CatalogueFailures.safe(tasklet).execute(
                step.createStepContribution(), new ChunkContext(new StepContext(step)));
    }

    void finish(int status) {
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(status);
        }
    }

    String captured(String text) throws Exception {
        try (var out = publication.create(9L)) { out.write(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
        String work = publication.workName(9L);
        job.getExecutionContext().putString(ListingPublication.WORK, work);
        job.getExecutionContext().putString(ListingPublication.HASH, publication.hashWork(work));
        return work;
    }

    @Test
    void intentIsPersistedBeforePublicationAndSurvivesRestart() throws Exception {
        input("captured\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(execute()).isEqualTo(RepeatStatus.CONTINUABLE);
        assertThat(publication.targetExists()).isFalse();
        verify(repository).updateExecutionContext(job);
        finish(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(job.getExecutionContext().containsKey(ListingPublication.WORK)).isTrue();
        assertThat(execute()).isEqualTo(RepeatStatus.FINISHED);
        assertThat(Files.readString(publication.directory().resolve("PRTCATBL.txt"))).isEqualTo("captured\n");
    }

    @Test
    void lostIntentCommitDoesNotLeakIntentIntoLaterMetadataSave() throws Exception {
        input(new byte[0]);
        execute();
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(job.getExecutionContext().containsKey(ListingPublication.WORK)).isFalse();
        assertThat(job.getExecutionContext().containsKey(ListingPublication.HASH)).isFalse();
        assertThat(publication.targetExists()).isFalse();
    }

    @Test
    void committedIntentBuildsNothingEvenWhenInputWouldChange() throws Exception {
        captured("original capture");
        assertThat(execute()).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(db);
        assertThat(Files.readString(publication.directory().resolve("PRTCATBL.txt")))
                .isEqualTo("original capture");
    }

    @Test
    void lostLeaseCannotPublishEvenAnOtherwiseValidIntent() throws Exception {
        String work = captured("original capture");
        when(leases.renew(1L)).thenReturn(false);
        assertThatThrownBy(this::execute).isInstanceOf(LeaseLostException.class);
        assertThat(keeper.isLost(1L)).isTrue();
        assertThat(publication.targetExists()).isFalse();
        assertThat(Files.exists(publication.directory().resolve(work))).isTrue();
        verifyNoInteractions(db);
    }

    @Test
    void publicationRenewsBeforeTheReadOnlyInstanceFence() throws Exception {
        captured("original capture");
        BoundListingRoot roots = mock(BoundListingRoot.class);
        when(roots.root(job)).thenReturn(root);
        tasklet = new CategoryListingTasklet(db, repository, outputs, roots,
                FidelitySwitches.allLegacy(BatchSwitches.ALL));
        execute();
        var ordered = inOrder(leases, roots);
        ordered.verify(leases).renew(job.getId());
        ordered.verify(roots).root(job);
    }

    @Test
    void existingTargetWithoutIntentFailsBeforeReadingInput() throws Exception {
        String work = captured("already published");
        OutputPublication.publishFile(publication.work(work), publication.target(),
                job.getExecutionContext().getString(ListingPublication.HASH));
        job.getExecutionContext().remove(ListingPublication.WORK);
        job.getExecutionContext().remove(ListingPublication.HASH);
        assertThatThrownBy(this::execute).hasMessageContaining("PUBLICATION_MISMATCH");
        verifyNoInteractions(db);
    }

    @Test
    void cleanupDeletesOnlyKnownTerminalUnreferencedWork() throws Exception {
        input(new byte[0]);
        var terminal = new JobExecution(2L, job.getJobInstance(), new JobParameters());
        terminal.setStatus(BatchStatus.FAILED);
        var named = new JobExecution(3L, job.getJobInstance(), new JobParameters());
        named.setStatus(BatchStatus.FAILED);
        named.getExecutionContext().putString(ListingPublication.WORK, publication.workName(3));
        for (long id : new long[] {2, 3, 400}) {
            try (var out = publication.create(id)) { out.write(1); }
        }
        when(repository.getJobExecutions(job.getJobInstance())).thenReturn(List.of(job, terminal, named));
        execute();
        assertThat(Files.exists(publication.directory().resolve(publication.workName(2)))).isFalse();
        assertThat(Files.exists(publication.directory().resolve(publication.workName(3)))).isTrue();
        assertThat(Files.exists(publication.directory().resolve(publication.workName(400)))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"carddemo.prtcatbl.workPath", "carddemo.prtcatbl.sha256"})
    void halfAnIntentFailsBeforeAnyInputOrLeaseStatement(String key) {
        job.getExecutionContext().putString(key, key.endsWith("workPath") ? publication.workName(9) : "0".repeat(64));
        assertThatThrownBy(this::execute).hasMessage("PUBLICATION_MISMATCH: publication refused");
        verifyNoInteractions(db, leases);
        assertThat(root.resolve("reports")).doesNotExist();
    }
}
