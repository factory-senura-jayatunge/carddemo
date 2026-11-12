package org.carddemo.batch.categorylisting;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.carddemo.batch.BatchServiceApplication;
import org.carddemo.batch.execution.LeaseKeeper;
import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.batch.internal.JobLaunches;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

/**
 * A real, killable owner for PublicationTransactionJobTest. Credentials arrive over stdin,
 * never in argv, files or logs. Only test beans pause the production job.
 */
public final class PublicationProbeChild {
    private static final AtomicBoolean PAUSED = new AtomicBoolean();
    private static Path control;
    private static String mode;
    private static long pausedExecution;

    private PublicationProbeChild() { }

    public static void main(String[] args) throws Exception {
        Properties input = new Properties();
        input.load(System.in);
        control = Path.of(input.getProperty("probe.control"));
        mode = input.getProperty("probe.mode");
        String runKey = input.getProperty("probe.run-key");
        Map<String, Object> settings = new HashMap<>();
        input.forEach((key, value) -> {
            if (!key.toString().startsWith("probe.")) settings.put(key.toString(), value);
        });
        SpringApplication app = new SpringApplication(BatchServiceApplication.class, Probe.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("private-publication-test", settings)));
        int exit = 0;
        try (var context = app.run()) {
            LeaseKeeper keeper = context.getBean(LeaseKeeper.class);
            keeper.suspendRenewals(true);
            try {
                context.getBean(JobLaunches.class).launch("categoryBalanceListingJob",
                        Map.of("params", Map.of("runKey", runKey, "businessDate", "2022-07-06")));
            } catch (RuntimeException failure) {
                if (!keeper.isLost(pausedExecution)) throw failure;
            }
            if (keeper.isLost(pausedExecution)) {
                signal("lost", "LEASE_LOST\n");
                exit = 23;
            }
        }
        System.exit(exit);
    }

    private static void pause(JobExecution execution) throws Exception {
        if (!PAUSED.compareAndSet(false, true)) return;
        pausedExecution = execution.getId();
        signal("ready", execution.getId() + "\n" + execution.getJobInstance().getId() + "\n");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!Files.exists(control.resolve("resume"))) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("publication test gate timed out");
            Thread.sleep(25);
        }
    }

    private static void signal(String name, String body) throws Exception {
        Path temporary = Files.createFile(control.resolve(name + ".tmp"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(temporary, body, StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
        Files.move(temporary, control.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        // The parent created this private 0700 control folder. No credentials or record values
        // enter these files; the production output paths retain their own 0700/0600 checks.
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {
        @Bean
        static BeanPostProcessor publicationGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof ExecutionLeases leases && mode.equals("before-renew")) {
                        ExecutionLeases probe = spy(leases);
                        doAnswer(invocation -> {
                            var step = StepSynchronizationManager.getContext();
                            boolean explicitPublication = StackWalker.getInstance().walk(frames -> frames
                                    .anyMatch(frame -> frame.getClassName().equals(CategoryListingTasklet.class.getName())));
                            if (step != null && explicitPublication) {
                                var execution = step.getStepExecution().getJobExecution();
                                if (execution.getExecutionContext().containsKey(ListingPublication.WORK)) {
                                    pause(execution);
                                }
                            }
                            return invocation.callRealMethod();
                        }).when(probe).renew(anyLong());
                        return probe;
                    }
                    if (bean instanceof JobRepository repository && mode.equals("after-link")) {
                        return Proxy.newProxyInstance(JobRepository.class.getClassLoader(),
                                new Class<?>[] {JobRepository.class}, (proxy, method, args) -> {
                                    if (method.getName().equals("updateExecutionContext") && args.length == 1
                                            && args[0] instanceof JobExecution execution
                                            && execution.getExecutionContext()
                                                    .containsKey(ExecutionOutputs.OUTPUT_PREFIX + "listing")) {
                                        pause(execution);
                                    }
                                    try {
                                        return method.invoke(repository, args);
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    }
                    return bean;
                }
            };
        }
    }
}
