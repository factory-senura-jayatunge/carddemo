package org.carddemo.batch.data;

/**
 * The two layouts an account record can have been written in (DEF-ACUP-002). Every reader uses
 * {@link #CVACT01Y}: the ZIP at 103-112 and the group ID at 113-122. Only the COACTUPC save writes
 * {@link #ACUP_UPDATE}: the group ID at 103 and FILLER it never sets from 113. The bytes cannot
 * show which one wrote a record, so the layout is declared by a manifest, never inferred.
 */
public enum AccountLayout {
    CVACT01Y,
    ACUP_UPDATE;

    AccountLayout other() {
        return this == CVACT01Y ? ACUP_UPDATE : CVACT01Y;
    }
}
