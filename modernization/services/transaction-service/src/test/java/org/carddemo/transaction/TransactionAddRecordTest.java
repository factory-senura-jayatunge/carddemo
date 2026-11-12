package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.carddemo.platform.legacyformat.entity.TransactionRecord;
import org.carddemo.transaction.TransactionAddRepository.DailyTranBody;
import org.carddemo.transaction.TransactionAddService.Checked;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * BR-TRNA-004 and NUM-TRNA-002: the queued row's {@code raw_record} is the 350-byte daily-file
 * record of CVTRA06Y, with a blank FILLER, and the row's timestamps are the date and 16 spaces.
 */
class TransactionAddRecordTest {

    static final String CARD = "4000000000000002";
    static final String TRAN_ID = "9000000000000000";

    static Checked checked(String description, String amount) {
        return new Checked("00000000050", CARD, "01", "0001", "POS TERM", description, new BigDecimal(amount),
                "-00000012.50", "2022-07-06", "123456789", "Shop", "Town", "12345");
    }

    private static String raw(Checked c) {
        DailyTranBody body = TransactionAddService.body(c);
        String procTs = "2026-11-07" + TransactionAddService.TIMESTAMP_PAD;
        byte[] bytes = TransactionAddService.record(TRAN_ID, c, body.origTs(), procTs).toDailyBytes();
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Test
    void theRecordHasTheDailyFileLayout() {
        String raw = raw(checked("Test purchase", "-12.50"));
        assertThat(raw).hasSize(350);
        assertThat(raw.substring(0, 16)).isEqualTo(TRAN_ID);
        assertThat(raw.substring(16, 18)).isEqualTo("01");
        assertThat(raw.substring(18, 22)).isEqualTo("0001");
        assertThat(raw.substring(22, 32)).isEqualTo("POS TERM  ");
        assertThat(raw.substring(32, 132)).isEqualTo("Test purchase" + " ".repeat(87));
        assertThat(raw.substring(132, 143)).as("S9(9)V99 with the negative overpunch").isEqualTo("0000000125}");
        assertThat(raw.substring(143, 152)).isEqualTo("123456789");
        assertThat(raw.substring(152, 202)).isEqualTo("Shop" + " ".repeat(46));
        assertThat(raw.substring(202, 252)).isEqualTo("Town" + " ".repeat(46));
        assertThat(raw.substring(252, 262)).isEqualTo("12345     ");
        assertThat(raw.substring(262, 278)).isEqualTo(CARD);
        assertThat(raw.substring(278, 304)).isEqualTo("2022-07-06" + " ".repeat(16));
        assertThat(raw.substring(304, 330)).isEqualTo("2026-11-07" + " ".repeat(16));
        assertThat(raw.substring(330, 350)).as("FILLER").isEqualTo(" ".repeat(20));
    }

    @Test
    void theRecordReadsBackAsTheDailyTransaction() {
        String raw = raw(checked("Test purchase", "12.50"));
        TransactionRecord back = TransactionRecord.parseDaily(raw.getBytes(StandardCharsets.ISO_8859_1));
        assertThat(back.tranId()).isEqualTo(TRAN_ID);
        assertThat(back.amount()).isEqualByComparingTo("12.50");
        assertThat(back.cardNum()).isEqualTo(CARD);
        assertThat(back.legacyFiller()).isEqualTo(" ".repeat(20));
        assertThat(raw.substring(132, 143)).isEqualTo("0000000125{");
    }

    /** S39: each character is one byte, U+00C9 for É. */
    @Test
    void latin1TextIsOneBytePerCharacter() {
        String raw = raw(checked("\u00c9".repeat(60), "-12.50"));
        byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 32; i < 92; i++) {
            assertThat(bytes[i] & 0xFF).isEqualTo(0xC9);
        }
        assertThat(raw.substring(92, 132)).isBlank();
    }

    @Test
    void theBodyHoldsWhatTheRowStores() {
        DailyTranBody body = TransactionAddService.body(checked("Test purchase", "-12.50"));
        assertThat(body.origTs()).isEqualTo("2022-07-06" + " ".repeat(16)).hasSize(26);
        assertThat(body.cardNum()).isEqualTo(CARD);
        assertThat(body.amount()).isEqualByComparingTo("-12.50");
    }

    /** S36: the card number is never printed in clear. */
    @Test
    void toStringsMaskTheCard() {
        Checked c = checked("Test purchase", "-12.50");
        assertThat(c.toString()).doesNotContain(CARD).contains("0002");
        assertThat(TransactionAddService.body(c).toString()).doesNotContain(CARD).contains("0002");
    }

    @Test
    void duplicateKeysAreToldApartByIndexName() {
        DuplicateKeyException waiting = new DuplicateKeyException("insert failed",
                new RuntimeException("Cannot insert duplicate key row in object 'core.daily_tran' with unique index"
                        + " 'UX_daily_waiting'."));
        assertThat(TransactionAddRepository.violates(waiting, TransactionAddRepository.WAITING_INDEX)).isTrue();
        assertThat(TransactionAddRepository.violates(waiting, TransactionAddRepository.IDEMPOTENCY_INDEX)).isFalse();
        assertThat(TransactionAddRepository.violates(new DuplicateKeyException(null),
                TransactionAddRepository.WAITING_INDEX)).isFalse();
    }
}
