package org.carddemo.platform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The ledger's arguments, checked before any statement runs; the database tests are in LedgerCoreTest. */
class LedgerArgumentsTest {

    static final LocalDateTime TS = LocalDateTime.of(2022, 7, 6, 10, 0);

    static NewTransaction transaction(Origin origin, Integer cycleId) {
        return new NewTransaction("0000000000000001", "01", "0001", "POS TERM", "PURCHASE", BigDecimal.ONE,
                "4111111111111111", "000000001", "SHOP", "TOWN", "10001", TS, TS, origin, null, cycleId);
    }

    @Test
    void theOnlineWritersConstructorLeavesTheCycleEmpty() {
        NewTransaction t = new NewTransaction("0000000000000001", "02", "0002", "POS TERM", "BILL PAYMENT - ONLINE",
                BigDecimal.TEN, "4111111111111111", "999999999", "BILL PAYMENT", "N/A", "N/A", TS, TS,
                Origin.BILL_PAY, "USER0001");
        assertThat(t.cycleId()).isNull();
        assertThat(t).isEqualTo(new NewTransaction("0000000000000001", "02", "0002", "POS TERM",
                "BILL PAYMENT - ONLINE", BigDecimal.TEN, "4111111111111111", "999999999", "BILL PAYMENT", "N/A",
                "N/A", TS, TS, Origin.BILL_PAY, "USER0001", null));
    }

    @Test
    void postingAndInterestNameTheirCycle() {
        for (Origin origin : new Origin[] {Origin.POSTED, Origin.INTEREST}) {
            assertThatThrownBy(() -> transaction(origin, null)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(origin.name());
            assertThat(transaction(origin, 7).cycleId()).isEqualTo(7);
        }
        for (Origin origin : new Origin[] {Origin.ONLINE_ADD, Origin.BILL_PAY, Origin.LOADED}) {
            assertThat(transaction(origin, null).cycleId()).isNull();
        }
        assertThatThrownBy(() -> transaction(Origin.POSTED, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transaction(Origin.POSTED, -3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewCategoryFillerIsTwentyTwoLegacyBytes() {
        String lowValues = "\u0000".repeat(22);
        String spaces = " ".repeat(22);
        String highBytes = "\u0080\u009F\u00A0\u00FF" + "x".repeat(18);
        for (String filler : new String[] {lowValues, spaces, highBytes}) {
            assertThat(PostedCategory.requireFiller(filler)).isSameAs(filler);
        }
        for (String filler : new String[] {null, "", " ".repeat(21), " ".repeat(23), "\u0100" + " ".repeat(21),
                " ".repeat(21) + "\u20AC"}) {
            assertThatThrownBy(() -> PostedCategory.requireFiller(filler)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anInvalidFillerIsRefusedBeforeAnyStatement() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcBalancePosting posting = new JdbcBalancePosting(jdbc);
        assertThatThrownBy(() -> posting.post("00000000001", "01", "0001", BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> posting.post("00000000001", "01", "0001", BigDecimal.ONE, "short"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
