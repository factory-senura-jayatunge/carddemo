package org.carddemo.platform.fidelity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Names the spec items a test proves, for example {@code @SpecItem({"BR-BILL-004", "SE-BILL-003"})}.
 * Class-level and method-level IDs are merged. {@link SpecItemExtension} checks the ID syntax and
 * records each test's items and result for the traceability matrix ({@code make trace}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(SpecItemExtension.class)
public @interface SpecItem {

    String[] value();
}
