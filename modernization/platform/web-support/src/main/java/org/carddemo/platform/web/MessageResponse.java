package org.carddemo.platform.web;

/** A success or neutral outcome the screen shows on its message line: {@code {message, severity, field}}. */
public record MessageResponse(String message, Severity severity, String field) {

    public static MessageResponse of(Message catalogMessage, String field, String... args) {
        return new MessageResponse(catalogMessage.render(args), catalogMessage.severity(), field);
    }
}
