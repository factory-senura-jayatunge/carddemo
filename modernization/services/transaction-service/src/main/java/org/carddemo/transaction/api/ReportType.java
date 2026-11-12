package org.carddemo.transaction.api;

/** The report a request asks for: the current month, the current year, or a range (BR-RPTS-001). */
public enum ReportType {
    MONTHLY("Monthly"),
    YEARLY("Yearly"),
    CUSTOM("Custom");

    private final String label;

    ReportType(String label) {
        this.label = label;
    }

    /** The {@code <name>} of the confirm and success texts. */
    public String label() {
        return label;
    }
}
