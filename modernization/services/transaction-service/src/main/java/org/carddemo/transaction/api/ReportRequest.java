package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A report request as typed: the six date parts are strings, because each part has its own
 * message and cursor (VAL-RPTS-002, VAL-RPTS-003); they are read for {@code CUSTOM} only.
 */
@Schema(name = "ReportRequest")
public record ReportRequest(
        @Schema(types = {"string", "null"}, description = "null when nothing is selected.") ReportType type,
        @Schema(examples = "07") String startMonth,
        @Schema(examples = "01") String startDay,
        @Schema(examples = "2022") String startYear,
        @Schema(examples = "07") String endMonth,
        @Schema(examples = "31") String endDay,
        @Schema(examples = "2022") String endYear,
        @Schema(description = "Blank asks for the confirmation; Y or y submits.", examples = "Y") String confirm) {
}
