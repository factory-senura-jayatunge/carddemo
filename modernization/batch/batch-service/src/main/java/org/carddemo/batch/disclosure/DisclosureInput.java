package org.carddemo.batch.disclosure;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.carddemo.batch.catalogue.CatalogueFailure;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.entity.DiscGroupRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FieldType;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;

/** DISCGRP is read once and fully validated before its reload transaction changes a row. */
final class DisclosureInput {
    private DisclosureInput() { }

    static List<DiscGroupRecord> read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new CatalogueFailure("INBOUND_FILE_MISSING", "discgrp");
        }
        try {
            var lines = LegacyFileReader.read(path, 50);
            List<DiscGroupRecord> records = new ArrayList<>(lines.size());
            String previous = null;
            boolean hasDefault = false;
            for (var line : lines) {
                String location = "discgrp line " + line.lineNumber();
                if (line.originalLength() != 50) {
                    throw new CatalogueFailure("RECORD_LENGTH", location);
                }
                try {
                    var view = line.view(Layouts.DISC_GROUP);
                    for (var field : Layouts.DISC_GROUP.fields()) {
                        if (field.type() == FieldType.FILLER && field.name().startsWith("FILLER-")
                                && !view.raw(field).equals(String.valueOf(field.fill()).repeat(field.length()))) {
                            throw new CatalogueFailure("FILLER_CONSTANT", location);
                        }
                        if (field.type() == FieldType.ZONED && view.isNegativeZero(field.name())) {
                            throw new CatalogueFailure("NEGATIVE_ZERO", location);
                        }
                    }
                    String key = new String(line.record(), 0, 16, StandardCharsets.ISO_8859_1);
                    if (previous != null && key.compareTo(previous) <= 0) {
                        throw new CatalogueFailure("VAL-JDISC-001", location);
                    }
                    DiscGroupRecord record = DiscGroupRecord.parse(line.record());
                    records.add(record);
                    hasDefault |= "DEFAULT".equals(record.groupId());
                    previous = key;
                } catch (LegacyFormatException e) {
                    throw new CatalogueFailure("RECORD_LAYOUT", location);
                }
            }
            if (!hasDefault) {
                throw new CatalogueFailure("DEFAULT_GROUP_MISSING", "discgrp line " + (lines.size() + 1));
            }
            return List.copyOf(records);
        } catch (LegacyFormatException e) {
            // The reader reports record numbers and sizes only, never record bytes.
            throw new CatalogueFailure("RECORD_LAYOUT", e.getMessage());
        } catch (UncheckedIOException e) {
            throw new CatalogueFailure("INBOUND_FILE_UNREADABLE", "discgrp");
        }
    }
}
