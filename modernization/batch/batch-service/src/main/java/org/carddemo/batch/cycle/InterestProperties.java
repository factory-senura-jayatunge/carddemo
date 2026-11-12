package org.carddemo.batch.cycle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * calculateInterestJob's settings.
 *
 * @param legacyParm the INTCALC JCL PARM, used as given while DEF-JINTC-001 is legacy; the default is
 *        the literal of {@code app/jcl/INTCALC.jcl}. CBACT04C uses its first 10 characters, and
 *        GnuCOBOL pads a shorter PARM with spaces (NUM-INTC-004).
 */
@ConfigurationProperties("carddemo.batch.interest")
public record InterestProperties(@DefaultValue("2022071800") String legacyParm) {

    static final int PARM_DATE_LENGTH = 10;

    public InterestProperties {
        if (legacyParm == null || legacyParm.isEmpty()) {
            throw new IllegalArgumentException("carddemo.batch.interest.legacy-parm must not be empty");
        }
    }

    /** PARM-DATE, PIC X(10). */
    String parmDate() {
        String padded = legacyParm + " ".repeat(PARM_DATE_LENGTH);
        return padded.substring(0, PARM_DATE_LENGTH);
    }
}
