# CardDemo interaction view

Who calls whom: online navigation (XCTL, LINK), the batch cycle and the data stores each step touches, and cross-module flows. It starts with the pilot slice around `COBIL00C` and `CBTRN02C` and grows as the area proposals in `areas/` are merged. Components and data stores are described in [components.md](components.md); record layouts in [entities.md](entities.md).

## Pilot slice: bill payment and daily posting

```mermaid
flowchart TB
    subgraph Online["CICS region (daytime)"]
        SGN[COSGN00C<br/>CC00 sign-on] --> MEN[COMEN01C<br/>CM00 main menu]
        MEN -- "option 10, XCTL" --> BIL[COBIL00C<br/>CB00 bill payment]
        BIL -- "PF3, XCTL" --> MEN
        BIL -- "no commarea, XCTL" --> SGN
        MEN --> TRA[COTRN02C<br/>CT02 transaction add]
    end
    subgraph Batch["Batch cycle (night)"]
        CLO[CLOSEFIL] --> PST[POSTTRAN<br/>CBTRN02C]
        PST --> INT[INTCALC<br/>CBACT04C]
        INT --> BKP[TRANBKP] --> CMB[COMBTRAN] --> STM[CREASTMT] --> IDX[TRANIDX] --> OPN[OPENFIL]
    end
    ACCT[(ACCTDATA)]
    XREF[(CARDXREF<br/>+ AIX by account)]
    TRAN[(TRANSACT)]
    TCB[(TCATBALF)]
    DLY[(DALYTRAN.PS)]
    REJ[(DALYREJS)]
    BIL -- "RU / U balance" --> ACCT
    BIL -- "R by account" --> XREF
    BIL -- "B highest ID, C payment" --> TRAN
    TRA -- "B highest ID, C" --> TRAN
    DLY --> PST
    PST -- "R by card" --> XREF
    PST -- "R / U balances" --> ACCT
    PST -- "R / C / U" --> TCB
    PST -- "C" --> TRAN
    PST -- "C" --> REJ
    TCB --> INT
    INT -- "U" --> ACCT
```
