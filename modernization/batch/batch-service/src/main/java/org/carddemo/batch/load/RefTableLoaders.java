package org.carddemo.batch.load;

import java.util.HashSet;
import java.util.Set;

import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.legacyformat.entity.DiscGroupRecord;
import org.carddemo.platform.legacyformat.entity.TranCategoryRecord;
import org.carddemo.platform.legacyformat.entity.TranTypeRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The load steps of the reference tables that have a sample file, types before their categories. */
final class RefTableLoaders {

    /** The group INTCALC falls back to for an account whose group has no rate (1200-A-GET-DEFAULT-INT-RATE). */
    static final String DEFAULT_GROUP = "DEFAULT";

    private RefTableLoaders() {
    }

    /** TRANTYPE into ref.tran_type. */
    static final class TransactionTypes extends TableLoadTasklet {
        TransactionTypes(JdbcClient db, DataLoadProperties p, FidelitySwitches s) {
            super(db, p, s);
        }

        @Override String stepName() { return "loadTransactionTypes"; }
        @Override String fileName() { return "trantype.txt"; }
        @Override RecordLayout layout() { return Layouts.TRAN_TYPE; }
        @Override int keyLength() { return 2; }
        @Override String table() { return "ref.tran_type"; }

        @Override
        void insert(SourceFile file, SourceLine line) {
            refRows.insert(file.convert(line, TranTypeRecord::parse));
        }
    }

    /** TRANCATG into ref.tran_category; every type it names must be loaded. */
    static final class Categories extends TableLoadTasklet {
        private Set<String> types = Set.of();

        Categories(JdbcClient db, DataLoadProperties p, FidelitySwitches s) {
            super(db, p, s);
        }

        @Override String stepName() { return "loadCategories"; }
        @Override String fileName() { return "trancatg.txt"; }
        @Override RecordLayout layout() { return Layouts.TRAN_CATEGORY; }
        @Override int keyLength() { return 6; }
        @Override String table() { return "ref.tran_category"; }

        @Override
        void beforeInsert(SourceFile file) {
            types = new HashSet<>(db.sql("SELECT type_cd FROM ref.tran_type").query(String.class).list());
        }

        @Override
        void insert(SourceFile file, SourceLine line) {
            TranCategoryRecord c = file.convert(line, TranCategoryRecord::parse);
            if (!types.contains(c.typeCd())) {
                throw new DataLoadException(file.where(line) + ": transaction type " + c.typeCd() + " is not loaded");
            }
            refRows.insert(c);
        }
    }

    /** DISCGRP into ref.disclosure_group; the file must hold the DEFAULT group. */
    static final class DisclosureGroups extends TableLoadTasklet {
        DisclosureGroups(JdbcClient db, DataLoadProperties p, FidelitySwitches s) {
            super(db, p, s);
        }

        @Override String stepName() { return "loadDisclosureGroups"; }
        @Override String fileName() { return "discgrp.txt"; }
        @Override RecordLayout layout() { return Layouts.DISC_GROUP; }
        @Override int keyLength() { return 16; }
        @Override String table() { return "ref.disclosure_group"; }

        @Override
        void beforeInsert(SourceFile file) {
            boolean hasDefault = file.lines().stream()
                    .anyMatch(l -> file.convert(l, DiscGroupRecord::parse).groupId().equals(DEFAULT_GROUP));
            if (!hasDefault) {
                throw new DataLoadException(file.name() + ": no " + DEFAULT_GROUP
                        + " group; interest falls back to it for accounts whose group has no rate");
            }
        }

        @Override
        void insert(SourceFile file, SourceLine line) {
            refRows.insert(file.convert(line, DiscGroupRecord::parse));
        }
    }
}
