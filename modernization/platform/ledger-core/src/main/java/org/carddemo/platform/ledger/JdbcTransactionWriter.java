package org.carddemo.platform.ledger;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link TransactionWriter} on core.transaction. */
public class JdbcTransactionWriter implements TransactionWriter {

    private final JdbcClient jdbc;

    public JdbcTransactionWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long insert(NewTransaction t) {
        try {
            Long pk = jdbc.sql("""
                            INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount,
                                merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts,
                                legacy_filler, origin, cycle_id, in_master, created_by)
                            OUTPUT INSERTED.tran_pk
                            VALUES (:tranId, :typeCd, :catCd, :source, :description, :amount, :merchantId,
                                :merchantName, :merchantCity, :merchantZip, :cardNum, :origTs, :procTs,
                                NULL, :origin, NULL, 1, :createdBy)""")
                    .param("tranId", t.tranId()).param("typeCd", t.typeCd()).param("catCd", t.catCd())
                    .param("source", t.source()).param("description", t.description()).param("amount", t.amount())
                    .param("merchantId", t.merchantId()).param("merchantName", t.merchantName())
                    .param("merchantCity", t.merchantCity()).param("merchantZip", t.merchantZip())
                    .param("cardNum", t.cardNum()).param("origTs", t.origTs()).param("procTs", t.procTs())
                    .param("origin", t.origin().name()).param("createdBy", t.createdBy())
                    .query(Long.class).single();
            return pk;
        } catch (DuplicateKeyException e) {
            throw new DuplicateTranIdException(t.tranId(), e);
        }
    }
}
