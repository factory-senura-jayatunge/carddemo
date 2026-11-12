package org.carddemo.verify.data;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of each check, printed one line per check. Details hold field names, file lines,
 * row ordinals and public codes, never values or account, customer or card keys.
 */
public final class Report {

    public enum Outcome { OK, FAIL, SKIP, INFO }

    public record Line(Outcome outcome, String subject, String detail) {
    }

    private final List<Line> lines = new ArrayList<>();

    void ok(String subject, String detail) {
        lines.add(new Line(Outcome.OK, subject, detail));
    }

    void fail(String subject, String detail) {
        lines.add(new Line(Outcome.FAIL, subject, detail));
    }

    void skip(String subject, String detail) {
        lines.add(new Line(Outcome.SKIP, subject, detail));
    }

    void info(String subject, String detail) {
        lines.add(new Line(Outcome.INFO, subject, detail));
    }

    public List<Line> lines() {
        return List.copyOf(lines);
    }

    public List<Line> failures() {
        return lines.stream().filter(l -> l.outcome() == Outcome.FAIL).toList();
    }

    public boolean ok() {
        return failures().isEmpty();
    }

    public void print(PrintStream out) {
        for (Line l : lines) {
            out.printf("%-5s %-24s %s%n", l.outcome().name().toLowerCase(java.util.Locale.ROOT), l.subject(), l.detail());
        }
    }
}
