package org.carddemo.account;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.carddemo.platform.validation.FormRules;
import org.carddemo.platform.validation.RuleTable;

/**
 * The labels {@code FIELD_NOT_EDITABLE} names for account update: the form's own labels from the
 * rule table, and those of the read-only fields. A label never comes from the request; a name
 * that is not here gets {@code FIELD_NOT_EDITABLE_UNKNOWN}.
 */
final class AccountUpdateLabels {

    /** Shown but not edited (BR-ACUP-008, D-08); the labels are the screen's. */
    static final Map<String, String> READ_ONLY = readOnly();

    private static final FormRules FORM = RuleTable.standard().form("accountUpdate");

    private AccountUpdateLabels() {
    }

    static Optional<String> of(String name) {
        String readOnly = READ_ONLY.get(name);
        return readOnly != null ? Optional.of(readOnly) : FORM.label(name);
    }

    /** Every known name with its label: the form fields in screen order, then the read-only fields. */
    static Map<String, String> all() {
        Map<String, String> all = new LinkedHashMap<>();
        for (String field : FORM.fields()) {
            all.put(field, FORM.label(field).orElseThrow());
        }
        all.putAll(READ_ONLY);
        return all;
    }

    private static Map<String, String> readOnly() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("acctId", "Account Number");
        m.put("custId", "Customer id");
        m.put("countryCd", "Country");
        m.put("currentBalance", "Current Balance");
        m.put("currCycCredit", "Current Cycle Credit");
        m.put("currCycDebit", "Current Cycle Debit");
        return java.util.Collections.unmodifiableMap(m);
    }
}
