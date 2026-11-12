package org.carddemo.verify.differential;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.carddemo.platform.outputroot.OutputRoot;

/** Hand-written stubs of the refusal mode's reads, environment and helper: no stack, no process. */
final class RefusalStubs {

    static final long CYCLE = 7;
    static final long LOAD = 40;
    static final long E = 41;
    static final int ATTEMPT = 2;
    static final LocalDate DATE = LocalDate.of(2022, 7, 6);
    static final LocalDateTime END = LocalDateTime.of(2022, 7, 6, 12, 0);
    static final String DATABASE = "carddemo_\u00e9";
    static final String INSTANCE_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    static final String CANARY = "CANARY-7f3a";

    static final RefusalReads.Execution FAILED = new RefusalReads.Execution(900, true, "FAILED", END, "FAILED",
            ExitTokens.TABLE_LIMIT);
    static final RefusalReads.Cycle CYCLE_ROW = new RefusalReads.Cycle(DATE, ATTEMPT);

    private RefusalStubs() {
    }

    static RefusalOptions options(Path outputDir) {
        return new RefusalOptions(CYCLE, LOAD, E, outputDir);
    }

    static OutputRoot.Instance instance() {
        return OutputRoot.Instance.of(DATABASE, INSTANCE_ID);
    }

    /** Throws a checked exception through the instance read. */
    interface InstanceRead {
        OutputRoot.Instance get() throws SQLException;
    }

    /**
     * Reads that answer a valid refusal by default. Each answer is a function of its call number
     * (1 for the first call), so a case can change what step 2 or step 4 sees; {@code calls} keeps
     * the order of every read.
     */
    static final class Reads implements RefusalReads {

        final List<String> calls = new ArrayList<>();
        IntFunction<Optional<Execution>> execution = n -> Optional.of(FAILED);
        IntFunction<Optional<Cycle>> cycle = n -> Optional.of(CYCLE_ROW);
        IntFunction<List<StatementExecution>> above = n -> List.of(new StatementExecution(ATTEMPT, E));
        List<String> runs = List.of("FAILED");
        InstanceRead instance = RefusalStubs::instance;
        final Map<String, RuntimeException> failing = new HashMap<>();

        private int count(String name) {
            calls.add(name);
            RuntimeException failure = failing.get(name);
            if (failure != null) {
                throw failure;
            }
            return (int) calls.stream().filter(name::equals).count();
        }

        @Override
        public Optional<Execution> execution(long executionId) {
            int n = count("execution");
            return executionId == E ? execution.apply(n) : Optional.empty();
        }

        @Override
        public Optional<Cycle> cycle(long cycleId) {
            int n = count("cycle");
            return cycleId == CYCLE ? cycle.apply(n) : Optional.empty();
        }

        @Override
        public List<StatementExecution> statementExecutionsAbove(long cycleId, Cycle row, long loadExecutionId) {
            return above.apply(count("above"));
        }

        @Override
        public List<String> statementRunStatuses(long cycleId, int attempt) {
            count("runs");
            return runs;
        }

        @Override
        public OutputRoot.Instance instance() throws SQLException {
            count("instance");
            return instance.get();
        }
    }

    /** An environment of fixed entries that records every name it is asked for. */
    static final class Environment implements Function<String, String> {

        final Map<String, String> values = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        Environment(Path varDir) {
            values.put(OutputRoot.VAR_DIR_VARIABLE, varDir.toString());
        }

        @Override
        public String apply(String name) {
            asked.add(name);
            return values.get(name);
        }
    }

    /** Step 3 without a process: records its arguments, runs a hook, then answers. */
    static final class Walk implements RefusalRun.WorkFolder {

        final List<Object> arguments = new ArrayList<>();
        Runnable hook = () -> { };
        RefusalRun.Facts facts = new RefusalRun.Facts(true, "empty");
        RuntimeException failure;
        int calls;

        @Override
        public RefusalRun.Facts walk(Path varDir, OutputRoot.Instance instance, long cycleId, int attempt,
                long executionId) {
            calls++;
            arguments.addAll(List.of(varDir, instance, cycleId, attempt, executionId));
            hook.run();
            if (failure != null) {
                throw failure;
            }
            return facts;
        }
    }
}
