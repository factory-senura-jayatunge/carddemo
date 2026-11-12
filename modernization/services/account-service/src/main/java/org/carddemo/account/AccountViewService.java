package org.carddemo.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import org.carddemo.account.AccountViewRepository.AccountRow;
import org.carddemo.account.AccountViewRepository.CustomerRow;
import org.carddemo.account.AccountViewRepository.XrefRow;
import org.carddemo.account.api.AccountView;
import org.carddemo.account.api.CrossReference;
import org.carddemo.account.api.CustomerView;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.carddemo.platform.web.Unmask;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * COACTVWC: the account, then its lowest card's cross-reference row, then that card's customer
 * (D-61). The first failing step is the only answer; a failed lookup never returns an earlier row
 * (DEF-ACVW-018).
 */
@Service
public class AccountViewService {

    /** The account fields an ADMIN may unmask, in the order the invalid-field message names them. */
    static final List<String> UNMASKABLE = List.of("ssn", "dob", "govtIssuedId", "eftAccountId", "cardNum");

    private static final String ACCT_ID = "acctId";
    private static final String SUBJECT_TYPE = "ACCOUNT";

    private final AccountViewRepository repository;
    private final AuditLog audit;
    private final SafeTransactionTemplate readTx;
    private final SafeTransactionTemplate tx;

    public AccountViewService(AccountViewRepository repository, JdbcClient jdbc,
                              SafeTransactionTemplate accountReadTransaction, SafeTransactionTemplate accountTransaction) {
        this.repository = repository;
        this.audit = new AuditLog(jdbc, "account-service");
        this.readTx = accountReadTransaction;
        this.tx = accountTransaction;
    }

    public AccountView view(String acctId) {
        requireAcctId(acctId);
        return readTx.execute(status -> {
            AccountRow account = account(acctId);
            Optional<XrefRow> xref = xref(acctId);
            if (xref.isEmpty()) {
                return view(account, null, null);
            }
            String custId = xref.get().custId();
            CustomerRow customer = customer(custId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                    MessageCatalog.CUSTOMER_NOT_FOUND, ACCT_ID, custId));
            return view(account, xref.get(), customer);
        });
    }

    /**
     * The requested fields in clear, after one audit row in the same transaction. With no card or
     * no customer the fields they would give are null.
     */
    public Map<String, String> unmask(String acctId, List<String> fields, String userId) {
        requireAcctId(acctId);
        SortedSet<String> names = Unmask.requireFields(new Unmask.Request(fields), UNMASKABLE);
        return tx.execute(status -> {
            account(acctId);
            Optional<XrefRow> xref = xref(acctId);
            Optional<CustomerRow> customer = xref.isEmpty() ? Optional.empty() : customer(xref.get().custId());
            try {
                audit.record(userId, AuditLog.Action.UNMASK, SUBJECT_TYPE, acctId, names);
            } catch (DataAccessException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.UNMASK_AUDIT_FAILED, null, e);
            }
            Map<String, String> clear = new LinkedHashMap<>();
            for (String name : names) {
                clear.put(name, clearValue(name, xref, customer));
            }
            return clear;
        });
    }

    private static String clearValue(String name, Optional<XrefRow> xref, Optional<CustomerRow> customer) {
        if (name.equals("cardNum")) {
            return xref.map(XrefRow::cardNum).orElse(null);
        }
        return customer.map(c -> switch (name) {
            case "ssn" -> AccountFormats.ssnDisplay(c.ssn());
            case "dob" -> AccountFormats.date(c.dob());
            case "govtIssuedId" -> c.govtIssuedId();
            case "eftAccountId" -> c.eftAccountId();
            default -> throw new IllegalArgumentException(name);
        }).orElse(null);
    }

    /** VAL-ACVW-001 and VAL-ACVW-002, on the value exactly as received. */
    static void requireAcctId(String acctId) {
        String rest = acctId != null && acctId.startsWith("*") ? acctId.substring(1) : acctId;
        if (rest == null || rest.chars().allMatch(c -> c == ' ')) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.ACVW_ACCT_ID_REQUIRED, ACCT_ID);
        }
        if (!acctId.matches("[0-9]{11}") || acctId.equals("00000000000")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.ACVW_INVALID_ACCT_ID, ACCT_ID);
        }
    }

    private AccountRow account(String acctId) {
        return read(MessageCatalog.ACCOUNT_DATA_READ_FAILED, () -> repository.account(acctId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.ACCOUNT_ID_NOT_FOUND, ACCT_ID,
                        acctId));
    }

    private Optional<XrefRow> xref(String acctId) {
        return read(MessageCatalog.XREF_DATA_READ_FAILED, () -> repository.firstCard(acctId));
    }

    private Optional<CustomerRow> customer(String custId) {
        return read(MessageCatalog.CUSTOMER_LOOKUP_FAILED, () -> repository.customer(custId));
    }

    private static <T> Optional<T> read(Message failure, Supplier<Optional<T>> read) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, failure, ACCT_ID, e);
        }
    }

    private static AccountView view(AccountRow a, XrefRow xref, CustomerRow c) {
        boolean noCard = xref == null;
        Message notice = MessageCatalog.NO_CARD_LINKED;
        return new AccountView(a.acctId(), a.activeStatus(), AccountFormats.date(a.openDate()),
                AccountFormats.date(a.expirationDate()), AccountFormats.date(a.reissueDate()),
                AccountFormats.amount(a.creditLimit()), AccountFormats.amountDisplay(a.creditLimit()),
                AccountFormats.amount(a.cashCreditLimit()), AccountFormats.amountDisplay(a.cashCreditLimit()),
                AccountFormats.amount(a.currBal()), AccountFormats.amountDisplay(a.currBal()),
                AccountFormats.amount(a.currCycCredit()), AccountFormats.amountDisplay(a.currCycCredit()),
                AccountFormats.amount(a.currCycDebit()), AccountFormats.amountDisplay(a.currCycDebit()),
                AccountFormats.groupId(a.groupId()),
                noCard ? null : new CrossReference(xref.custId(),
                        xref.cardId() == null ? null : String.valueOf(xref.cardId()), Pii.cardNumber(xref.cardNum())),
                c == null ? null : customer(c),
                noCard ? notice.code() : null, noCard ? notice.render() : null,
                noCard ? notice.severity() : null, noCard ? ACCT_ID : null);
    }

    private static CustomerView customer(CustomerRow c) {
        return new CustomerView(c.custId(), Pii.ssn(c.ssn()), c.dob() == null ? Pii.text("") : Pii.date(c.dob()),
                AccountFormats.fico(c.ficoScore()), c.firstName(), c.middleName(), c.lastName(), c.addrLine1(),
                c.addrLine2(), c.addrLine3(), c.stateCd(), c.zip(), c.countryCd(), c.phone1(), c.phone2(),
                Pii.text(c.govtIssuedId()), Pii.text(c.eftAccountId()), c.priCardHolderInd());
    }
}
