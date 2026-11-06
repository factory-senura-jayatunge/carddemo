package org.carddemo.platform.web;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How the screen shows a message: red, green or neutral. */
public enum Severity {
    ERROR,
    SUCCESS,
    INFO;

    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
