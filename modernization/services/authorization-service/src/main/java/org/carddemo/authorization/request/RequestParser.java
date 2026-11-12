package org.carddemo.authorization.request;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.carddemo.authorization.request.UnparsableRequestException.Reason;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/**
 * Reads one request body (COPAUA0C §4.1, BR-AUTH-001; messaging.md). The form follows the
 * message's {@code content_type}: {@code text/csv}, {@code text/plain} or none for the 18
 * comma-separated values, {@code application/json} for the object of named values. A body that
 * cannot be read throws {@link UnparsableRequestException}; a readable body with invalid values is
 * a request like any other and is declined by the decision.
 */
public final class RequestParser {

    private static final int VALUES = RequestField.values().length;
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final Map<String, RequestField> BY_JSON_NAME = new HashMap<>();

    static {
        for (RequestField f : RequestField.values()) {
            BY_JSON_NAME.put(f.jsonName(), f);
        }
    }

    private RequestParser() {
    }

    public static AuthorizationRequest parse(byte[] body, String contentType) throws UnparsableRequestException {
        BodyForm form = form(contentType);
        if (body.length > form.maxBytes()) {
            throw new UnparsableRequestException(Reason.OVERSIZED);
        }
        String text = decode(body);
        Map<RequestField, String> raw = form == BodyForm.CSV ? csv(text) : json(text);
        return new AuthorizationRequest(form, raw);
    }

    static BodyForm form(String contentType) throws UnparsableRequestException {
        if (contentType == null || contentType.isBlank()) {
            return BodyForm.CSV;
        }
        String[] parts = contentType.split(";");
        String type = parts[0].trim().toLowerCase(Locale.ROOT);
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=", 2);
            if (kv[0].trim().equalsIgnoreCase("charset")
                    && (kv.length < 2 || !unquote(kv[1].trim()).equalsIgnoreCase("utf-8"))) {
                throw new UnparsableRequestException(Reason.UNKNOWN_CONTENT_TYPE);
            }
        }
        return switch (type) {
            case "text/csv", "text/plain" -> BodyForm.CSV;
            case "application/json" -> BodyForm.JSON;
            default -> throw new UnparsableRequestException(Reason.UNKNOWN_CONTENT_TYPE);
        };
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static String decode(byte[] body) throws UnparsableRequestException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new UnparsableRequestException(Reason.UNDECODABLE);
        }
    }

    /**
     * Split on every comma, as {@code UNSTRING ... DELIMITED BY ','}: a comma inside a value shifts
     * the later fields, and values after the 18th are ignored. Fewer than 18 values cannot be
     * completed from anywhere else (D-31), so the request is unparsable.
     */
    private static Map<RequestField, String> csv(String text) throws UnparsableRequestException {
        String[] values = text.split(",", -1);
        if (values.length < VALUES) {
            throw new UnparsableRequestException(Reason.TOO_FEW_VALUES);
        }
        Map<RequestField, String> raw = new EnumMap<>(RequestField.class);
        RequestField[] fields = RequestField.values();
        for (int i = 0; i < VALUES; i++) {
            raw.put(fields[i], values[i]);
        }
        return raw;
    }

    /**
     * Every one of the 18 names must be present with a string; the amount may also be a JSON
     * number, taken in its textual form. Unknown names are ignored. Duplicate names, trailing
     * content, {@code null} or any other type make the body unparsable.
     */
    private static Map<RequestField, String> json(String text) throws UnparsableRequestException {
        Map<RequestField, String> raw = new EnumMap<>(RequestField.class);
        try (JsonParser p = JSON.createParser(ObjectReadContext.empty(), text)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new UnparsableRequestException(Reason.INVALID_JSON);
            }
            for (JsonToken t = p.nextToken(); t != JsonToken.END_OBJECT; t = p.nextToken()) {
                RequestField field = BY_JSON_NAME.get(p.currentName());
                JsonToken value = p.nextToken();
                if (field == null) {
                    p.skipChildren();
                    continue;
                }
                if (value == JsonToken.VALUE_STRING) {
                    raw.put(field, p.getString());
                } else if (field == RequestField.TRANSACTION_AMT
                        && (value == JsonToken.VALUE_NUMBER_INT || value == JsonToken.VALUE_NUMBER_FLOAT)) {
                    raw.put(field, p.getText());
                } else {
                    throw new UnparsableRequestException(Reason.INVALID_JSON);
                }
            }
            if (p.nextToken() != null) {
                throw new UnparsableRequestException(Reason.INVALID_JSON);
            }
        } catch (JacksonException e) {
            throw new UnparsableRequestException(Reason.INVALID_JSON);
        }
        if (raw.size() != VALUES) {
            throw new UnparsableRequestException(Reason.INVALID_JSON);
        }
        return raw;
    }
}
