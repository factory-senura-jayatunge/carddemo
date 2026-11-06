package org.carddemo.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The equivalence scenarios of the CSUTLDTC spec (section 15), written out independently of the vectors. */
class LegacyDateCheckTest {

    @ParameterizedTest(name = "{0} [{1}] -> {2}/{3}")
    @SpecItem({"BR-DTCK-002", "BR-DTCK-006", "NUM-DTCK-002"})
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "2022-07-06|YYYY-MM-DD|0|0|Date is valid",
        "2024-02-29|YYYY-MM-DD|0|0|Date is valid",
        "1582-10-15|YYYY-MM-DD|0|0|Date is valid",
        "9999-12-31|YYYY-MM-DD|0|0|Date is valid",
        "2023-02-29|YYYY-MM-DD|3|2508|Datevalue error",
        "2022-04-31|YYYY-MM-DD|3|2508|Datevalue error",
        "2022-07-00|YYYY-MM-DD|3|2508|Datevalue error",
        "2022-13-01|YYYY-MM-DD|3|2517|Invalid month",
        "2022-00-10|YYYY-MM-DD|3|2517|Invalid month",
        "1582-10-14|YYYY-MM-DD|3|2513|Unsupp. Range",
        "0022-01-01|YYYY-MM-DD|3|2513|Unsupp. Range",
        "0000-01-01|YYYY-MM-DD|3|2513|Unsupp. Range",
        "1500-01-01|YYYY-MM-DD|3|2513|Unsupp. Range",
        "\"2022-7-6  \"|YYYY-MM-DD|3|2520|Nonnumeric data",
        "2022-0A-01|YYYY-MM-DD|3|2520|Nonnumeric data",
        "2022/07/06|YYYY-MM-DD|0|0|Date is valid",
        "\"          \"|YYYY-MM-DD|3|2507|Insufficient",
        "2022-07-06|YYYY-XX-DD|3|2518|Bad Pic String",
        "2022-07-06|\"          \"|3|2518|Bad Pic String",
        "20220706|YYYYMMDD|0|0|Date is valid",
        "20230229|YYYYMMDD|3|2508|Datevalue error",
    })
    void specScenarios(String date, String picture, int severity, int message, String verdict) {
        DateCheckResult r = LegacyDateCheck.check(date, picture);
        assertThat(r.severity()).isEqualTo(severity);
        assertThat(r.messageNumber()).isEqualTo(message);
        assertThat(r.verdict()).isEqualTo(verdict);
    }

    @Test
    @SpecItem("BR-DTCK-003")
    void resultLineHasTheFixedLayout() {
        assertThat(LegacyDateCheck.check("2022-07-06", LegacyDateCheck.DASHED).line())
                .isEqualTo("0000Mesg Code: 0000 Date is valid   TstDate: 2022-07-06 Mask used:YYYY-MM-DD    ");
        DateCheckResult bad = LegacyDateCheck.check("2023-02-29", LegacyDateCheck.DASHED);
        assertThat(bad.line())
                .isEqualTo("0003Mesg Code: 2508 Datevalue error TstDate: 2023-02-29 Mask used:YYYY-MM-DD    ");
        assertThat(bad.severityCode()).isEqualTo(bad.line().substring(0, 4));
        assertThat(bad.messageCode()).isEqualTo(bad.line().substring(15, 19));
    }

    @Test
    @SpecItem({"BR-DTCK-001", "FLOW-DTCK-002"})
    void dateAndPictureAreTenCharacterFields() {
        assertThat(LegacyDateCheck.check(null, LegacyDateCheck.DASHED).messageNumber()).isEqualTo(2507);
        assertThat(LegacyDateCheck.check("2022-07-06", null).messageNumber()).isEqualTo(2518);
        assertThat(LegacyDateCheck.check("2022-07-06 trailing", LegacyDateCheck.DASHED).testedDate()).isEqualTo("2022-07-06");
        // The account update layout: 8 digits with the picture YYYYMMDD and two trailing bytes (DEF-DTCK-003).
        assertThat(LegacyDateCheck.check("20220706", LegacyDateCheck.COMPACT).testedDate()).isEqualTo("20220706  ");
        assertThat(LegacyDateCheck.check("20220706", LegacyDateCheck.COMPACT).picture()).isEqualTo("YYYYMMDD  ");
    }

    @Test
    @SpecItem("BR-DTCK-005")
    void valueIsTheDateOnlyWhenValid() {
        assertThat(LegacyDateCheck.check("2024-02-29", LegacyDateCheck.DASHED).value()).contains(LocalDate.of(2024, 2, 29));
        assertThat(LegacyDateCheck.check("1582-10-14", LegacyDateCheck.DASHED).value()).isEmpty();
    }
}
