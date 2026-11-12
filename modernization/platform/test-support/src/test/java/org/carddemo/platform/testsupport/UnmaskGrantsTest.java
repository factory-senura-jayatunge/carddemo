package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import org.carddemo.db.LoginCheck;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * batch_svc's UNMASK is by column (R__grants_batch.sql): it reads the customer's four masked columns
 * and the card numbers of the master and the staged rows as stored, and the CVV masked (D-DATA-04).
 * Every login's UNMASK in the catalog is exactly its ServiceLogin.unmaskedColumns().
 */
class UnmaskGrantsTest {

    private static final String ACCT = "00000000901";
    private static final String CUST = "000000901";
    private static final String CARD = "4000000000000901";

    private final TestDatabase db = TestDatabase.shared();

    @BeforeEach
    void rows() {
        db.fixtures().deleteAll();
        CoreFixtures.Customer c = CoreFixtures.Customer.of(CUST);
        db.fixtures().account(CoreFixtures.Account.of(ACCT, "10.00"))
                .customer(new CoreFixtures.Customer(c.custId(), c.firstName(), c.middleName(), c.lastName(),
                        c.addrLine1(), c.addrLine2(), c.addrLine3(), c.stateCd(), c.countryCd(), c.zip(), c.phone1(),
                        c.phone2(), "123456789", "GOVT0901", LocalDate.of(1971, 3, 4), "9876543210",
                        c.priCardHolderInd(), c.ficoScore()))
                .card(CARD, ACCT, CUST)
                .transaction("0000000000000901", CARD, "1.00")
                .waitingOnlineAdd("0000000000000902", CARD, "2.00");
    }

    @AfterEach
    void clean() {
        db.fixtures().deleteAll();
    }

    @Test
    void batchReadsThePiiAndCardNumbersAsStoredAndTheCvvMasked() {
        JdbcClient batch = JdbcClient.create(db.dataSource(ServiceLogin.BATCH));
        Map<String, Object> customer = batch.sql("SELECT ssn, govt_issued_id, dob, eft_account_id FROM core.customer"
                + " WHERE cust_id = :c").param("c", CUST).query().singleRow();
        assertThat(customer).containsEntry("ssn", "123456789").containsEntry("govt_issued_id", "GOVT0901")
                .containsEntry("eft_account_id", "9876543210");
        assertThat(customer.get("dob").toString()).isEqualTo("1971-03-04");
        assertThat(batch.sql("SELECT card_num FROM core.[transaction] WHERE tran_id = '0000000000000901'")
                .query(String.class).single()).isEqualTo(CARD);
        assertThat(batch.sql("SELECT card_num FROM core.daily_tran WHERE tran_id = '0000000000000902'")
                .query(String.class).single()).isEqualTo(CARD);

        String stored = db.admin().sql("SELECT cvv_cd FROM core.card WHERE card_num = :c").param("c", CARD)
                .query(String.class).single();
        String seen = batch.sql("SELECT cvv_cd FROM core.card WHERE card_num = :c").param("c", CARD)
                .query(String.class).single();
        assertThat(stored).isEqualTo("000");
        assertThat(seen).isEqualTo("xxx");
    }

    @Test
    void everyLoginHoldsExactlyItsUnmaskColumns() throws SQLException {
        try (Connection admin = db.adminDataSource().getConnection()) {
            assertThat(LoginCheck.unmaskFailures(admin)).isEmpty();
        }
    }
}
