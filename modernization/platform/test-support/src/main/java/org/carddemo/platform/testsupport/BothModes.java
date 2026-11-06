package org.carddemo.platform.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs every test of the class twice, with the listed switches all {@code legacy}, then all
 * {@code fixed}. The mode reaches the application through {@link FidelityOverrides} (Spring tests
 * pick it up by {@link TestFidelityAutoConfiguration}), the test through
 * {@link FidelityOverrides#current()}, and the spec-item records through
 * {@code SpecItemExtension.recordMode}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ClassTemplate
@ExtendWith(BothModesExtension.class)
public @interface BothModes {

    /** The switches set together, for example {@code {"DEF-BILL-010", "BR-TRNA-003"}}. */
    String[] value();
}
