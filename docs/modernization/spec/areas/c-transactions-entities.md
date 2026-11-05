# Area c-transactions: entity proposals

Proposals from area `c-transactions` (COTRN00C, COTRN01C, COTRN02C, CORPT00C, CSUTLDTC) for `../entities.md`, written against commit b5e0139. Layout as in `README.md` of this folder.

## ENT-DATE-CHECK-RESULT · Result line of the date check CSUTLDTC

- **Defined in:** `app/cbl/CSUTLDTC.cbl:42-57` (`WS-MESSAGE`, 80 bytes), returned in the third parameter `LS-RESULT` (`app/cbl/CSUTLDTC.cbl:86, 97`). Caller copies: `app/cbl/COTRN02C.cbl:65-69`, `app/cbl/CORPT00C.cbl:132-136` (`CSUTLDTC-RESULT`), `app/cpy/CSUTLDWY.cpy:60-80` (`WS-DATE-VALIDATION-RESULT`, used by COACTUPC).
- **Storage:** none; a parameter area only.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `WS-SEVERITY` (`-N`) | 1 | 4 | `X(04)` / `9(4)` | digits(4) | LE condition severity; `0000` = valid date, `0003` for every CEEDAYS error |
| FILLER | 5 | 11 | `X(11)` | text(11) | `Mesg Code: ` |
| `WS-MSG-NO` (`-N`) | 16 | 4 | `X(04)` / `9(4)` | digits(4) | LE message number (`2507`, `2508`, `2509`, `2513`, `2517`, `2518`, `2520`, `2521`, or `0000`) |
| FILLER | 20 | 1 | `X(01)` | text(1) | space |
| `WS-RESULT` | 21 | 15 | `X(15)` | text(15) | verdict, for example `Date is valid`, `Datevalue error` (CSUTLDTC BR-DTCK-002) |
| FILLER | 36 | 1 | `X(01)` | text(1) | space |
| FILLER | 37 | 9 | `X(09)` | text(9) | `TstDate: ` |
| `WS-DATE` | 46 | 10 | `X(10)` | text(10) | date as given by the caller |
| FILLER | 56 | 1 | `X(01)` | text(1) | space |
| FILLER | 57 | 10 | `X(10)` | text(10) | `Mask used:` |
| `WS-DATE-FMT` | 67 | 10 | `X(10)` | text(10) | picture as given by the caller |
| FILLER | 77 | 4 | `X(01)`, `X(03)` | text(4) | spaces |

The callers name positions 1-4 `CSUTLDTC-RESULT-SEV-CD` and 16-19 `CSUTLDTC-RESULT-MSG-NUM` (`app/cbl/COTRN02C.cbl:66-68`), and positions 20-80 `CSUTLDTC-RESULT-MSG` (61 bytes, never read).
