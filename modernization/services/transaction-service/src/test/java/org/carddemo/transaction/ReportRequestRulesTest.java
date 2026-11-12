package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.carddemo.platform.web.ApiException;
import org.carddemo.transaction.ReportRequestRules.Range;
import org.carddemo.transaction.api.ReportRequest;
import org.carddemo.transaction.api.ReportType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

/** The checks of a report request before anything is written (CORPT00C, scenarios S3 to S12). */
class ReportRequestRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2022, 7, 6);
    private static final String[] PARTS = {"startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear"};

    static ReportRequest custom(String... parts) {
        return new ReportRequest(ReportType.CUSTOM, parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], "Y");
    }

    static ReportRequest of(ReportType type) {
        return new ReportRequest(type, null, null, null, null, null, null, "Y");
    }

    static void refused(Executable check, String code, String message, String field) {
        assertThatThrownBy(check::execute).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.text()).isEqualTo(message);
            assertThat(e.field()).isEqualTo(field);
        });
    }

    /** S1, S2, S3: the clock's date, not the business date, sets the month and the year. */
    @Test
    void monthlyAndYearlyFollowTheClock() {
        assertThat(ReportRequestRules.range(of(ReportType.MONTHLY), TODAY))
                .isEqualTo(new Range(LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31)));
        assertThat(ReportRequestRules.range(of(ReportType.YEARLY), TODAY))
                .isEqualTo(new Range(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 12, 31)));
        assertThat(ReportRequestRules.range(of(ReportType.MONTHLY), LocalDate.of(2024, 2, 10)))
                .isEqualTo(new Range(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)));
        assertThat(ReportRequestRules.range(of(ReportType.MONTHLY), LocalDate.of(2022, 12, 15)))
                .isEqualTo(new Range(LocalDate.of(2022, 12, 1), LocalDate.of(2022, 12, 31)));
    }

    /** The custom parts are ignored for the other types. */
    @Test
    void monthlyIgnoresTheCustomParts() {
        ReportRequest request = new ReportRequest(ReportType.MONTHLY, "13", "", "x", null, null, null, "Y");
        assertThat(ReportRequestRules.range(request, TODAY).start()).isEqualTo(LocalDate.of(2022, 7, 1));
    }

    /** S6. */
    @Test
    void aTypeIsRequired() {
        refused(() -> ReportRequestRules.range(of(null), TODAY), "REPORT_TYPE_REQUIRED",
                "Select a report type to print report...", "monthly");
        refused(() -> ReportRequestRules.range(null, TODAY), "REPORT_TYPE_REQUIRED",
                "Select a report type to print report...", "monthly");
    }

    /** S7: each part blank in turn gives its own text; spaces count as blank. */
    @Test
    void eachPartIsRequiredInOrder() {
        String[] texts = {"Start Date - Month can NOT be empty...", "Start Date - Day can NOT be empty...",
                "Start Date - Year can NOT be empty...", "End Date - Month can NOT be empty...",
                "End Date - Day can NOT be empty...", "End Date - Year can NOT be empty..."};
        String[] codes = {"START_MONTH_REQUIRED", "START_DAY_REQUIRED", "START_YEAR_REQUIRED", "END_MONTH_REQUIRED",
                "END_DAY_REQUIRED", "END_YEAR_REQUIRED"};
        for (int i = 0; i < PARTS.length; i++) {
            String[] parts = {"07", "01", "2022", "07", "31", "2022"};
            parts[i] = i % 2 == 0 ? "  " : null;
            ReportRequest request = custom(parts);
            refused(() -> ReportRequestRules.range(request, TODAY), codes[i], texts[i], PARTS[i]);
        }
        refused(() -> ReportRequestRules.range(custom("", "", "", "", "", ""), TODAY), "START_MONTH_REQUIRED",
                texts[0], "startMonth");
    }

    /** Every required check runs before any form check. */
    @Test
    void requiredComesBeforeForm() {
        refused(() -> ReportRequestRules.range(custom("13", "32", "22", "07", "31", ""), TODAY),
                "END_YEAR_REQUIRED", "End Date - Year can NOT be empty...", "endYear");
    }

    /** S8: a part's length, digits and maximum; a two-digit or non-digit year is refused. */
    @Test
    void eachPartHasItsForm() {
        refused(() -> ReportRequestRules.range(custom("13", "01", "2022", "07", "31", "2022"), TODAY),
                "START_MONTH_INVALID", "Start Date - Not a valid Month...", "startMonth");
        refused(() -> ReportRequestRules.range(custom("07", "32", "2022", "07", "31", "2022"), TODAY),
                "START_DAY_INVALID", "Start Date - Not a valid Day...", "startDay");
        refused(() -> ReportRequestRules.range(custom("07", "01", "22", "07", "31", "2022"), TODAY),
                "START_YEAR_INVALID", "Start Date - Not a valid Year...", "startYear");
        refused(() -> ReportRequestRules.range(custom("07", "01", "20a2", "07", "31", "2022"), TODAY),
                "START_YEAR_INVALID", "Start Date - Not a valid Year...", "startYear");
        refused(() -> ReportRequestRules.range(custom("007", "01", "2022", "07", "31", "2022"), TODAY),
                "START_MONTH_INVALID", "Start Date - Not a valid Month...", "startMonth");
        refused(() -> ReportRequestRules.range(custom("07", "01", "2022", "1a", "31", "2022"), TODAY),
                "END_MONTH_INVALID", "End Date - Not a valid Month...", "endMonth");
        refused(() -> ReportRequestRules.range(custom("07", "01", "2022", "07", "3 1", "2022"), TODAY),
                "END_DAY_INVALID", "End Date - Not a valid Day...", "endDay");
        refused(() -> ReportRequestRules.range(custom("07", "01", "2022", "07", "31", "02022"), TODAY),
                "END_YEAR_INVALID", "End Date - Not a valid Year...", "endYear");
    }

    /** Month 00 and day 00 pass the form check and fail as dates. */
    @Test
    void zeroPartsFailAsDates() {
        refused(() -> ReportRequestRules.range(custom("00", "01", "2022", "07", "31", "2022"), TODAY),
                "START_DATE_INVALID", "Start Date - Not a valid date...", "startMonth");
        refused(() -> ReportRequestRules.range(custom("07", "01", "2022", "07", "00", "2022"), TODAY),
                "END_DATE_INVALID", "End Date - Not a valid date...", "endMonth");
    }

    /** S9, S10: the calendar, and 2513 (before 1582-10-15) is invalid. */
    @Test
    void datesGoThroughTheDateCheck() {
        refused(() -> ReportRequestRules.range(custom("02", "30", "2022", "07", "31", "2022"), TODAY),
                "START_DATE_INVALID", "Start Date - Not a valid date...", "startMonth");
        refused(() -> ReportRequestRules.range(custom("01", "01", "1500", "12", "31", "2022"), TODAY),
                "START_DATE_INVALID", "Start Date - Not a valid date...", "startMonth");
        refused(() -> ReportRequestRules.range(custom("01", "01", "2022", "02", "29", "2022"), TODAY),
                "END_DATE_INVALID", "End Date - Not a valid date...", "endMonth");
        assertThat(ReportRequestRules.range(custom("02", "29", "2024", "02", "29", "2024"), TODAY))
                .isEqualTo(new Range(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29)));
    }

    /** S11. */
    @Test
    void theStartMayNotBeAfterTheEnd() {
        refused(() -> ReportRequestRules.range(custom("07", "31", "2022", "07", "01", "2022"), TODAY),
                "DATE_RANGE_INVALID", "Start Date can NOT be after End Date...", "startMonth");
    }

    /** S12: one-digit parts, with or without a leading space, are padded. */
    @Test
    void oneDigitPartsArePadded() {
        assertThat(ReportRequestRules.range(custom(" 7", " 1", "2022", "7", "31", "2022 "), TODAY))
                .isEqualTo(new Range(LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31)));
    }

    /** S4: blank asks for the confirmation, with the resolved range. */
    @Test
    void aBlankConfirmAsksWithTheRange() {
        Range range = new Range(LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31));
        for (String blank : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> ReportRequestRules.confirm(blank, ReportType.MONTHLY, range))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                        assertThat(e.code()).isEqualTo("CONFIRM_REQUIRED");
                        assertThat(e.text()).isEqualTo("Please confirm to print the Monthly report...");
                        assertThat(e.field()).isEqualTo("confirm");
                        assertThat(e.extras()).containsEntry("startDate", "2022-07-01")
                                .containsEntry("endDate", "2022-07-31");
                    });
        }
        assertThatThrownBy(() -> ReportRequestRules.confirm("", ReportType.CUSTOM, range))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.text()).isEqualTo("Please confirm to print the Custom report..."));
    }

    /** S5: anything but Y or y is refused, echoing its first character only. */
    @Test
    void onlyYConfirms() {
        Range range = new Range(LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31));
        assertThatCode(() -> ReportRequestRules.confirm("Y", ReportType.CUSTOM, range)).doesNotThrowAnyException();
        assertThatCode(() -> ReportRequestRules.confirm("y", ReportType.CUSTOM, range)).doesNotThrowAnyException();
        refused(() -> ReportRequestRules.confirm("X", ReportType.CUSTOM, range), "CONFIRM_INVALID",
                "\"X\" is not a valid value to confirm...", "confirm");
        refused(() -> ReportRequestRules.confirm("XYZ", ReportType.CUSTOM, range), "CONFIRM_INVALID",
                "\"X\" is not a valid value to confirm...", "confirm");
        refused(() -> ReportRequestRules.confirm("YES", ReportType.CUSTOM, range), "CONFIRM_INVALID",
                "\"Y\" is not a valid value to confirm...", "confirm");
    }
}
