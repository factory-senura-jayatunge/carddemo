package org.carddemo.batch.internal;

import java.util.regex.Pattern;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Refuses to start when {@code carddemo.batch.command} names nothing that runs: an unknown command,
 * {@code none} without the internal API, or load or cycle-rerun with
 * {@code spring.batch.job.enabled=false} (which turns both commands off) would start no command
 * and exit 0 as if it had done its work, and a cycle-rerun without a valid cycle ID would do
 * nothing either. {@link org.carddemo.batch.BatchServiceApplication#main(String[])} adds it, so that it runs
 * once the configuration is read, before any connection is opened, and the failed start exits the
 * JVM non-zero. Test contexts, which start no command on purpose, do not go through main.
 */
public class BatchCommandCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final String COMMAND = "carddemo.batch.command";
    static final String CYCLE_ID = "carddemo.batch.rerun.cycle-id";
    static final String JOBS_ENABLED = "spring.batch.job.enabled";
    private static final Pattern CYCLE_ID_DIGITS = Pattern.compile("[1-9][0-9]{0,9}");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        check(event.getEnvironment());
    }

    static void check(Environment environment) {
        String command = environment.getProperty(COMMAND, "");
        boolean oneShot = command.equalsIgnoreCase("load") || command.equalsIgnoreCase("cycle-rerun");
        if (oneShot && !environment.getProperty(JOBS_ENABLED, Boolean.class, true)) {
            throw new IllegalStateException(command + " does not run with " + JOBS_ENABLED + "=false");
        }
        if (command.equalsIgnoreCase("load")) {
            return;
        }
        if (command.equalsIgnoreCase("cycle-rerun")) {
            if (!isCycleId(environment.getProperty(CYCLE_ID, "").strip())) {
                throw new IllegalStateException("cycle-rerun needs " + CYCLE_ID + ", a cycle ID");
            }
            return;
        }
        // Without the property Spring Boot deduces a servlet application from the classpath.
        boolean serves = !environment.getProperty("spring.main.web-application-type", "servlet").equalsIgnoreCase("none");
        if (command.equalsIgnoreCase("none") && serves) {
            return;
        }
        throw new IllegalStateException(COMMAND + " must be load or cycle-rerun, or none with the profile server, not '"
                + (command.length() > 40 ? command.substring(0, 40) + "..." : command) + "'");
    }

    /** A value batch.cycle.cycle_id, an INT IDENTITY, can hold: 1 to 2147483647, digits only. */
    static boolean isCycleId(String value) {
        return CYCLE_ID_DIGITS.matcher(value).matches() && Long.parseLong(value) <= Integer.MAX_VALUE;
    }

    /** After Spring Boot's own listeners, which read the configuration files and arguments. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
