package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** A report run as {@code batch.report_run} holds it, with the stalled flag of {@code batch.v_report_run_stalled}. */
@Schema(name = "ReportRun")
public record ReportRun(
        @Schema(examples = "17") long runId,
        ReportType type,
        @Schema(description = "YYYY-MM-DD.", examples = "2022-07-01") String startDate,
        @Schema(description = "YYYY-MM-DD.", examples = "2022-07-31") String endDate,
        @Schema(examples = "USER0001") String requestedBy,
        @Schema(description = "UTC, milliseconds.", examples = "2026-11-10T14:02:11.120Z") String requestedAt,
        ReportStatus status,
        @Schema(description = "The run's job stopped renewing its lease.") boolean stalled,
        @Schema(types = {"string", "null"}, description = "UTC, milliseconds.") String startedAt,
        @Schema(types = {"string", "null"}, description = "UTC, milliseconds.") String finishedAt,
        @Schema(types = {"string", "null"}, description = "TRIGGER_FAILED, JOB_FAILED, JOB_STOPPED or OWNER_LOST.")
        String failureCode,
        @Schema(description = "True only when COMPLETED.") boolean downloadable,
        @Schema(types = {"string", "null"}, description = "On the 202 only (BR-RPTS-008).",
                examples = "Custom report submitted for printing ...") String message) {
}
