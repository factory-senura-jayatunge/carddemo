package org.carddemo.batch.categorylisting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.carddemo.batch.internal.InternalApiException;
import org.carddemo.batch.internal.JobLaunches;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.testsupport.CoreFixtures.Account;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Contract 4.7: actual JVM death and competing recovery around the real publication transaction. */
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "carddemo.batch.lease.renewal-interval=500ms",
        "carddemo.batch.lease.ttl=2s",
        "carddemo.batch.lease.grace=2s"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Isolated("Rotates the private test JVM's database instance marker; no concurrent test may use that database")
@Execution(ExecutionMode.SAME_THREAD)
class PublicationTransactionJobTest {
    private static final TestDatabase DB = TestDatabase.shared();
    Path varDir;
    @TempDir Path control;
    @Autowired JobLaunches launches;
    @Autowired JobRepository repository;
    @Autowired OutputRoots roots;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @BeforeEach
    void inputRows() {
        varDir = roots.varDir();
        DB.fixtures().deleteAll().closeBatchWindows().account(Account.of("00000000001", "0.00"))
                .categoryBalance("00000000001", "01", "0001", "-12.34");
    }

    private Child child(String mode, String runKey) throws Exception {
        return child(mode, runKey, "2s");
    }

    private Child child(String mode, String runKey, String ttl) throws Exception {
        assertThat(varDir).isEqualTo(roots.varDir());
        Path log = Files.createFile(control.resolve("child.log"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx256m", "-Duser.timezone=UTC", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                PublicationProbeChild.class.getName());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        Properties input = new Properties();
        input.setProperty("spring.datasource.url", DB.jdbcUrl());
        input.setProperty("spring.datasource.username", DB.loginName(ServiceLogin.BATCH));
        input.setProperty("spring.datasource.password", DB.password(ServiceLogin.BATCH));
        input.setProperty("spring.batch.job.enabled", "false");
        input.setProperty("carddemo.batch.async-launch", "false");
        input.setProperty("carddemo.var-dir", varDir.toString());
        input.setProperty("carddemo.batch.lease.renewal-interval", "500ms");
        input.setProperty("carddemo.batch.lease.ttl", ttl);
        input.setProperty("carddemo.batch.lease.grace", "2s");
        input.setProperty("probe.control", control.toString());
        input.setProperty("probe.mode", mode);
        input.setProperty("probe.run-key", runKey);
        try (var stdin = process.getOutputStream()) {
            input.store(stdin, null);
        } catch (Exception e) {
            process.destroyForcibly();
            throw e;
        } finally {
            input.clear();
        }
        Child owner = new Child(process);
        try {
            owner.awaitReady();
            return owner;
        } catch (Exception | AssertionError e) {
            owner.close();
            throw e;
        }
    }

    private JobExecution launch(String key) {
        JobExecution execution = launches.launch("categoryBalanceListingJob",
                Map.of("params", Map.of("runKey", key, "businessDate", "2022-07-06"))).execution();
        return repository.getJobExecution(execution.getId());
    }

    private Path directory(long instanceId) throws Exception {
        // Assertions derive the path independently; production still uses the required N1 binding.
        try (var connection = DB.adminDataSource().getConnection()) {
            return OutputRoot.root(varDir.toRealPath(), OutputRoot.read(connection))
                    .resolve("reports/prtcatbl/" + instanceId);
        }
    }

    private Map<String, String> fileHashes(long instanceId) throws Exception {
        return fileHashes(directory(instanceId));
    }

    private Map<String, String> fileHashes(Path directory) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        if (!Files.exists(directory)) return hashes;
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                hashes.put(directory.relativize(path).toString(), ListingPublication.hash(path));
            }
        }
        return hashes;
    }

    private void expire(long id) {
        DB.admin().sql("""
                UPDATE batch.execution_lease SET renewed_at = DATEADD(MINUTE, -10, SYSUTCDATETIME())
                WHERE job_execution_id = :id
                """).param("id", id).update();
    }

    @Test
    void killedAfterTheLinkBeforeCommitRestartsFromThePublishedCapture() throws Exception {
        String key = "publication-kill/" + UUID.randomUUID();
        try (Child owner = child("after-link", key)) {
            assertThat(fileHashes(owner.instanceId)).containsOnlyKeys("PRTCATBL.txt");
            var before = fileHashes(owner.instanceId);
            assertThat(repository.getJobExecution(owner.executionId).getExecutionContext()
                    .containsKey(ExecutionOutputsKey.LISTING)).isFalse();
            assertThat(repository.getJobExecution(owner.executionId).getExecutionContext()
                    .containsKey(ListingPublication.HASH)).isTrue();
            owner.kill();
            expire(owner.executionId);
            // A changed input must not rebuild the listing after the captured intent.
            DB.admin().sql("UPDATE core.tcat_bal SET balance = 99.99").update();
            JobExecution restarted = launch(key);
            assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(restarted.getId()).isNotEqualTo(owner.executionId);
            assertThat(fileHashes(owner.instanceId)).isEqualTo(before);
            assertThat(restarted.getExecutionContext().containsKey(ExecutionOutputsKey.LISTING)).isTrue();
        }
    }

    @Test
    void recoveryDuringPublishGets409AndChangesNeitherFilesNorExecution() throws Exception {
        String key = "publication-lock/" + UUID.randomUUID();
        try (Child owner = child("after-link", key)) {
            var before = fileHashes(owner.instanceId);
            Thread.sleep(Duration.ofMillis(4500));
            assertThatThrownBy(() -> launch(key)).isInstanceOfSatisfying(InternalApiException.class, problem -> {
                assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(problem.code()).isEqualTo("JOB_EXECUTION_RUNNING");
            });
            assertThat(repository.getJobExecution(owner.executionId).getStatus()).isEqualTo(BatchStatus.STARTED);
            assertThat(DB.admin().sql("""
                    SELECT COUNT(*) FROM batch.execution_lease
                    WHERE job_execution_id = :id AND revoked_at IS NOT NULL
                    """).param("id", owner.executionId).query(Integer.class).single()).isZero();
            assertThat(fileHashes(owner.instanceId)).isEqualTo(before);
            owner.resume();
            owner.awaitExit(0);
            assertThat(repository.getJobExecution(owner.executionId).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(fileHashes(owner.instanceId)).isEqualTo(before);
        }
    }

    @Test
    void anOwnerRecoveredBeforePublishGetsLeaseLostAndCannotChangeItsSuccessorsFiles() throws Exception {
        String key = "publication-stale/" + UUID.randomUUID();
        try (Child owner = child("before-renew", key)) {
            assertThat(fileHashes(owner.instanceId)).containsOnlyKeys("PRTCATBL.txt." + owner.executionId + ".tmp");
            Thread.sleep(Duration.ofMillis(4500));
            JobExecution successor = launch(key);
            assertThat(successor.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            var committed = fileHashes(owner.instanceId);
            assertThat(committed).containsOnlyKeys("PRTCATBL.txt");
            owner.resume();
            owner.awaitExit(23);
            assertThat(Files.readString(control.resolve("lost"))).isEqualTo("LEASE_LOST\n");
            assertThat(fileHashes(owner.instanceId)).isEqualTo(committed);
            assertThat(repository.getJobExecution(successor.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }
    }

    @Test
    void markerRotationAfterIntentRefusesPublicationAndPreservesCapturedWork() throws Exception {
        String original = DB.admin().sql("""
                SELECT CONVERT(VARCHAR(36), instance_id) FROM ops.database_instance WHERE id = 1
                """).query(String.class).single();
        UUID replacement = UUID.randomUUID();
        // This test exercises the instance fence, not lease expiry while its gate is paused.
        try (Child owner = child("before-renew", "publication-rotation/" + UUID.randomUUID(), "60s")) {
            JobExecution intent = repository.getJobExecution(owner.executionId);
            assertThat(intent.getExecutionContext().containsKey(ListingPublication.HASH)).isTrue();
            assertThat(intent.getExecutionContext().containsKey(ListingPublication.WORK)).isTrue();
            assertThat(intent.getExecutionContext().containsKey(ExecutionOutputsKey.LISTING)).isFalse();
            Path capturedDirectory = directory(owner.instanceId);
            var captured = fileHashes(capturedDirectory);
            assertThat(captured).containsOnlyKeys("PRTCATBL.txt." + owner.executionId + ".tmp");
            Path replacementRoot = OutputRoot.root(varDir.toRealPath(), OutputRoot.Instance.of(
                    intent.getExecutionContext().getString(OutputRoots.DATABASE_KEY), replacement.toString()));
            Path originalRoot = OutputRoot.root(varDir.toRealPath(), OutputRoot.Instance.of(
                    intent.getExecutionContext().getString(OutputRoots.DATABASE_KEY), original));
            String filesystemMarker = ListingPublication.hash(originalRoot.resolve(".instance"));
            assertThat(replacementRoot).doesNotExist();

            // Only the private SQL identity rotates. The original filesystem .instance is not edited.
            assertThat(DB.admin().sql("""
                    UPDATE ops.database_instance SET instance_id = CONVERT(UNIQUEIDENTIFIER, :instance) WHERE id = 1
                    """).param("instance", replacement.toString()).update()).isEqualTo(1);
            owner.resume();
            owner.awaitExit(0);

            JobExecution failed = repository.getJobExecution(owner.executionId);
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failed.getExitStatus().getExitDescription())
                    .isEqualTo("OUTPUT_ROOT_MISMATCH: output root refused");
            assertThat(failed.getExecutionContext().containsKey(ExecutionOutputsKey.LISTING)).isFalse();
            assertThat(fileHashes(capturedDirectory)).isEqualTo(captured);
            assertThat(capturedDirectory.resolve("PRTCATBL.txt")).doesNotExist();
            assertThat(ListingPublication.hash(originalRoot.resolve(".instance"))).isEqualTo(filesystemMarker);
            assertThat(replacementRoot).doesNotExist();
        } finally {
            // The child is closed before restoring the marker, including on assertion failure.
            assertThat(DB.admin().sql("""
                    UPDATE ops.database_instance SET instance_id = CONVERT(UNIQUEIDENTIFIER, :instance) WHERE id = 1
                    """).param("instance", original).update()).isEqualTo(1);
            assertThat(DB.admin().sql("""
                    SELECT CONVERT(VARCHAR(36), instance_id) FROM ops.database_instance WHERE id = 1
                    """).query(String.class).single()).isEqualToIgnoringCase(original);
        }
    }

    private static final class ExecutionOutputsKey {
        static final String LISTING = org.carddemo.batch.internal.ExecutionOutputs.OUTPUT_PREFIX + "listing";
    }

    private final class Child implements AutoCloseable {
        private final Process process;
        private long executionId;
        private long instanceId;

        Child(Process process) { this.process = process; }

        void awaitReady() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (!Files.exists(control.resolve("ready"))) {
                if (!process.isAlive() || System.nanoTime() >= deadline) {
                    throw new AssertionError("publication child did not reach the gate; inspect its private log");
                }
                Thread.sleep(25);
            }
            var ids = Files.readAllLines(control.resolve("ready"));
            executionId = Long.parseLong(ids.get(0));
            instanceId = Long.parseLong(ids.get(1));
        }

        void resume() throws Exception {
            Files.writeString(control.resolve("resume"), "", StandardOpenOption.CREATE_NEW);
        }

        void kill() throws Exception {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
        }

        void awaitExit(int expected) throws Exception {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(expected);
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            // Join is uninterruptible: the outer marker-restoration finally cannot run while
            // this owner can still use the rotated database. The kill was sent before joining.
            process.onExit().join();
        }
    }
}
