package org.carddemo.platform.web;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One catalogue text.
 *
 * @param key unique catalogue key
 * @param code the API {@code code}; several keys may share one code (variants of a text)
 * @param text the exact text, with {@code {name}} placeholders for {@link #params}
 * @param severity how the screen shows it
 * @param source the spec item or legacy program the text comes from, or {@code target} for texts
 *     the modernized application adds
 */
public record Message(String key, String code, String text, Severity severity, String source) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");

    public Message {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(source, "source");
    }

    /** The placeholder names, in order of appearance. */
    public List<String> params() {
        Matcher m = PLACEHOLDER.matcher(text);
        return m.results().map(r -> r.group(1)).toList();
    }

    /** The text with each placeholder replaced by the argument in the same position. */
    public String render(String... args) {
        List<String> params = params();
        if (args.length != params.size()) {
            throw new IllegalArgumentException(key + " takes " + params + ", got " + args.length + " arguments");
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(text);
        int i = 0;
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(Objects.requireNonNull(args[i++], "argument")));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
