package org.carddemo.transaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.carddemo.platform.legacyformat.entity.TransactionRecord;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The start data of the scenarios. SAMPLE: the 300 records of {@code app/data/ASCII/dailytran.txt}
 * as master rows ({@code in_master = 1}, origin {@code POSTED}, {@code proc_ts = orig_ts}, as a
 * cycle posts them). F25: 25 master rows {@code I1 < ... < I25}.
 */
final class SampleTransactions {

    static final Path FILE = Path.of("../../../app/data/ASCII/dailytran.txt");
    /** CVTRA06Y: 350 bytes a record. */
    static final int RECORD = 350;
    static final String SAMPLE_CARD = "4859452612877065";
    static final String F25_CARD = "4000000000000002";

    /** One stored row: the record's fields with the timestamps as the database holds them. */
    record Row(TransactionRecord record, LocalDateTime origTs, LocalDateTime procTs) {
    }

    private SampleTransactions() {
    }

    /** The sample records in file order. */
    static List<Row> sample() {
        byte[] data;
        try {
            data = Files.readAllBytes(FILE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Row> rows = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= data.length; i++) {
            if (i == data.length || data[i] == '\n') {
                if (i > start) {
                    byte[] line = Arrays.copyOfRange(data, start, data[i - 1] == '\r' ? i - 1 : i);
                    if (line.length != RECORD) {
                        throw new IllegalStateException("record of " + line.length + " bytes in " + FILE);
                    }
                    TransactionRecord r = TransactionRecord.parseDaily(line);
                    LocalDateTime orig = timestamp(r.origTs());
                    rows.add(new Row(r, orig, orig));
                }
                start = i + 1;
            }
        }
        return rows;
    }

    /** The sample in ascending ID order, as the list shows it. */
    static List<Row> sampleById() {
        return sample().stream().sorted(Comparator.comparing(r -> r.record().tranId())).toList();
    }

    static void loadSample(JdbcClient admin) {
        for (Row row : sample()) {
            insert(admin, row, true);
        }
    }

    /** I1 to I25: {@code 0000000000000100} to {@code 0000000000002500}. */
    static String f25(int n) {
        return String.format("%016d", n * 100L);
    }

    static void loadF25(JdbcClient admin) {
        for (int n = 1; n <= 25; n++) {
            insertDefault(admin, f25(n), "1.00");
        }
    }

    /** A master row with neutral content apart from its ID and amount. */
    static void insertDefault(JdbcClient admin, String tranId, String amount) {
        LocalDateTime ts = LocalDateTime.of(2022, 7, 5, 10, 0);
        insert(admin, new Row(new TransactionRecord(tranId, "01", "0001", "POS TERM", "FIXTURE PURCHASE",
                new BigDecimal(amount), "000000001", "FIXTURE", "TOWN", "10001", F25_CARD, null, null, null), ts, ts),
                true);
    }

    static void insert(JdbcClient admin, Row row, boolean inMaster) {
        TransactionRecord r = row.record();
        admin.sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, in_master)
                VALUES (:id, :type, :cat, :source, :description, :amount, :merchantId, :merchantName, :merchantCity,
                    :merchantZip, :card, :orig, :proc, 'POSTED', :inMaster)""")
                .param("id", r.tranId()).param("type", r.typeCd()).param("cat", r.catCd())
                .param("source", r.source()).param("description", r.description()).param("amount", r.amount())
                .param("merchantId", r.merchantId()).param("merchantName", r.merchantName())
                .param("merchantCity", r.merchantCity()).param("merchantZip", r.merchantZip())
                .param("card", r.cardNum()).param("orig", row.origTs()).param("proc", row.procTs())
                .param("inMaster", inMaster)
                .update();
    }

    /** {@code YYYY-MM-DD HH:MM:SS.ffffff}, the daily input layout. */
    static LocalDateTime timestamp(String text) {
        return LocalDateTime.parse(text.substring(0, 26).replace(' ', 'T'));
    }
}
