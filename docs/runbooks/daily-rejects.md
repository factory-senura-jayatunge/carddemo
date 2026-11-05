# Daily transaction rejects (DALYREJS)

POSTTRAN (`CBTRN02C`) validates every record in `AWS.M2.CARDDEMO.DALYTRAN.PS`. Valid
transactions are posted; invalid ones are written to a new generation of
`AWS.M2.CARDDEMO.DALYREJS`. This runbook covers reading that file and getting rejected
transactions posted.

## How you know

At the end of POSTTRAN, SYSOUT shows:

```
TRANSACTIONS PROCESSED :nnnnnnnnn
TRANSACTIONS REJECTED  :nnnnnnnnn
```

When the reject count is above zero, `CBTRN02C` sets return code 4. The job has not failed.
The posted transactions are complete and the cycle can continue.

## The reject file

- Dataset: `AWS.M2.CARDDEMO.DALYREJS(+1)` at write time, `DALYREJS(0)` afterwards. The GDG
  base comes from the `DALYREJS` job, with `LIMIT(5) SCRATCH`, so only the last five days of
  rejects are kept.
- Record format: `RECFM=F`, `LRECL=430`.

| Bytes | Content |
| :---- | :------ |
| 1-350 | The original `DALYTRAN-RECORD` (layout `CVTRA06Y`) |
| 351-354 | `WS-VALIDATION-FAIL-REASON`, 4 digits |
| 355-430 | `WS-VALIDATION-FAIL-REASON-DESC`, 76 characters |

The record holds the full card number (`DALYTRAN-CARD-NUM`, bytes 263-278). Handle the file as
cardholder data; see [../security/data-handling.md](../security/data-handling.md).

## Reason codes

From `1500-VALIDATE-TRAN` in `app/cbl/CBTRN02C.cbl`:

| Code | Description in the record | Check performed | Usual fix |
| ---: | :------------------------ | :-------------- | :-------- |
| 100 | `INVALID CARD NUMBER FOUND` | `DALYTRAN-CARD-NUM` not found in `CARDXREF` | Load the card and its cross reference (CARDFILE, XREFFILE), or correct the card number |
| 101 | `ACCOUNT RECORD NOT FOUND` | The account from the cross reference is not in `ACCTDATA` | Load or correct the account (ACCTFILE) or the cross reference |
| 102 | `OVERLIMIT TRANSACTION` | `ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT` is greater than `ACCT-CREDIT-LIMIT` | Business decision: raise the limit (Account Update, CAUP) or leave the transaction rejected |
| 103 | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `ACCT-EXPIRAION-DATE` is earlier than the first 10 characters of `DALYTRAN-ORIG-TS` | Reissue or extend the account, or leave rejected |

Notes from the code:

- 102 and 103 are checked independently; if both fail, the record carries 103.
- Code 109 (`ACCOUNT RECORD NOT FOUND` on the account rewrite) is set after the transaction
  has been posted to `TCATBALF` and `TRANSACT`. It does not produce a reject record. If the
  job log shows posting anomalies with no matching reject, check the account file.

## Review the rejects

Print the latest generation with IDCAMS. `PRINT` takes the dataset from a DD, which accepts the
relative generation:

```
//PRTREJ   EXEC PGM=IDCAMS
//SYSPRINT DD SYSOUT=*
//REJS     DD DISP=SHR,DSN=AWS.M2.CARDDEMO.DALYREJS(0)
//SYSIN    DD *
  PRINT INFILE(REJS) CHARACTER
/*
```

Count rejects by reason code with ICETOOL:

```
//COUNT    EXEC PGM=ICETOOL
//TOOLMSG  DD SYSOUT=*
//DFSMSG   DD SYSOUT=*
//IN       DD DISP=SHR,DSN=AWS.M2.CARDDEMO.DALYREJS(0)
//OUT      DD SYSOUT=*
//TOOLIN   DD *
  OCCUR FROM(IN) LIST(OUT) ON(351,4,CH) ON(VALCNT)
/*
```

Mask card numbers in anything you share.

## Reprocess rejected transactions

1. Fix the reference data that caused each reject (table above).
2. Strip the 80-byte trailer to get back to the `DALYTRAN` layout:

   ```
   //STRIP    EXEC PGM=SORT
   //SORTIN   DD DISP=SHR,DSN=AWS.M2.CARDDEMO.DALYREJS(0)
   //SORTOUT  DD DSN=AWS.M2.CARDDEMO.DALYTRAN.REPROC,
   //            DISP=(NEW,CATLG,DELETE),UNIT=SYSDA,
   //            DCB=(RECFM=FB,LRECL=350,BLKSIZE=0),SPACE=(CYL,(1,1),RLSE)
   //SYSOUT   DD SYSOUT=*
   //SYSIN    DD *
     SORT FIELDS=COPY
     OUTREC FIELDS=(1,350)
   /*
   ```

   Filter with `INCLUDE COND=(351,4,CH,EQ,C'0100')` if you only fixed one reason.
3. Feed the records into the next cycle by concatenating `DALYTRAN.REPROC` after
   `DALYTRAN.PS` on the `DALYTRAN` DD of POSTTRAN, or by merging them into the next day's
   `DALYTRAN.PS`. Do not run POSTTRAN a second time in the same cycle: `TRANSACT` is no longer
   empty and the open fails (see [batch-cycle-restart.md](batch-cycle-restart.md)).
4. Keep the `DALYTRAN-ID` values unchanged; they become the `TRAN-ID` keys in `TRANSACT`.
5. Delete `DALYTRAN.REPROC` once the transactions are posted.

## Escalate

Raise an issue if many transactions fail with 100 or 101 after a fresh data load. The shipped
sample files `dailytran.txt`, `cardxref.txt` and `acctdata.txt` are expected to agree.
