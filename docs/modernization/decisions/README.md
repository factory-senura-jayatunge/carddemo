# CardDemo modernization: user decision catalog

The natural-language spec (`docs/modernization/spec/`) records what the COBOL does today, defects included. It holds 825 items that ask for a choice: every open question (`Q-` row), every item marked fidelity *defect-decide*, and every `[security]` defect. This catalog merges them into 75 decisions, gives each a recommendation, and lists the items that need no decision with the reason. **Until a decision is taken, the build keeps the reference COBOL behaviour.**

How to use it: read the short list, answer each decision with an option number (or "as recommended"), and the build follows the answer. Each decision names the spec items it settles, so the spec owners can mark them. Decisions in theme S7 apply only if the optional authorization module is built, S17 only if the VSAM-MQ module is built, and the Db2 parts of S15 only if the transaction type module is built.

## Short list: the decisions to take first

| # | Decision | Question | Recommendation | Priority |
| --: | :-- | :-- | :-- | :-- |
| 1 | [D-01](#d-01) Require sign-on for every screen and API | Must every screen and service require an authenticated session, with the user's identity and role taken only from that session? | Fix. Today the menus are reachable without a password, and a web client can send any field. | high |
| 2 | [D-02](#d-02) Enforce roles on every function | Which roles exist, which functions does each role get, and what happens to a user whose type is not a known role? | Fix with the two existing roles, deny unknown types; add a fraud role only if the authorization module is built. It closes the gap with the least new design. | high |
| 3 | [D-03](#d-03) Who may see and change which customers' data | Are regular users bank staff who may work on any customer, or cardholders who may see only their own accounts and cards? | Keep the staff model for parity, combined with sign-on, roles, masking and access audit; choose the cardholder model only if cardholders are users, because it needs a new data link. | high |
| 4 | [D-04](#d-04) Password storage and case rules | How are passwords stored and migrated, and are passwords and user IDs case-sensitive? | Salted hashes (bcrypt or Argon2), case-sensitive passwords, case-insensitive IDs, hash once at migration and force a reset; the current passwords have been readable in clear text. | high |
| 5 | [D-07](#d-07) Least privilege for services, jobs and the database | Should every service, job and integration client run with only the rights it needs, and must callers of the inquiry services authenticate? | Fix; it is standard for the target platform and has no business trade-off. | high |
| 6 | [D-08](#d-08) Direct editing of balances on account update | May the account update screen change the current balance and cycle credit and debit totals directly? | Fix. A direct edit bypasses the ledger, so balances and transactions stop agreeing. | high |
| 7 | [D-09](#d-09) Mask sensitive data on screens | Which sensitive fields are masked on screens, who may reveal them, and may the CVV be held in session state? | Fix as described; full display serves no screen function and is a compliance exposure. | high |
| 8 | [D-10](#d-10) Sensitive data in reports, logs and files | What may batch reports, job logs, extracts and export files contain? | Fix; logs and reports are the usual leak path, and nothing downstream needs the full values. | high |
| 9 | [D-11](#d-11) Account update writes the group ID into the ZIP code | Should the target store ZIP and disclosure group ID in their own fields, and how are the already-shifted sample accounts migrated? | Fix and migrate the group ID; the defect silently changes which interest rates apply. | high |
| 10 | [D-12](#d-12) Card update overwrites the CVV with 000 | Should a card save change only the fields the user edits? | Fix; the as-is save destroys the CVV. | high |
| 11 | [D-16](#d-16) Save account and customer together | Must an account update save the account and the customer in one transaction? | Fix; a half-saved update cannot be seen or corrected by the user. | high |
| 12 | [D-18](#d-18) Batch window: online access while batch runs | Must online functions stop while the batch cycle runs, or may they run alongside? | No outage with snapshot reads, if D-17 is accepted; otherwise keep the window. It removes a daily outage. | high |
| 13 | [D-19](#d-19) Over-limit rule in posting | Keep the over-limit formula (cycle credit minus cycle debit plus amount) or switch to a balance-based check? | Keep for the first release so posting matches the reference run, then change it as a separate business decision. | high |
| 14 | [D-23](#d-23) Prevent double posting and double interest | Must the target refuse to post the same daily file twice or charge interest twice in one cycle? | Fix; reruns otherwise change customer balances. | high |
| 15 | [D-24](#d-24) How often interest is charged | Is interest calculated monthly or in every batch cycle? | Monthly on a chosen day; it matches the monthly rate. | high |
| 16 | [D-27](#d-27) Transactions added online are never posted | Should a transaction added online change the account balance, and through which path? | Feed the posting input; it keeps one posting path and one set of rules. | high |
| 17 | [D-28](#d-28) Payments update cycle totals and category balances | Should an online payment update the cycle credit total and category balance like posting does, and with which sign? | Fix through the same posting service as D-27, after checking the effect on interest results; payments should count toward interest and statements. | high |
| 18 | [D-29](#d-29) Payment errors leave partial updates | Should a payment be all-or-nothing and stop at the first error? | Fix, unless operations name a reconciliation that relies on the partial updates. | high |
| 19 | [D-37](#d-37) Which batch cycle the scheduler runs | Which batch cycle do the Airflow DAGs implement (options A-F of `jobs/README.md` on nl/G)? | Option F; it is the only order consistent with every data hand-off. It needs D-24, D-38, D-39 and D-40. | high |
| 20 | [D-41](#d-41) Demo reloads of master data in production | May production run the delete-and-reload jobs that replace accounts, cards, customers, cross-references and category balances with the shipped sample data? | Seed-only; a production reload destroys customer data. | high |
| 21 | [D-43](#d-43) What a statement covers | Is a statement per account or per card, which period does it cover, and what must it show? | Fix; the as-is output is not a usable billing statement. Keep a parity mode only for the migration check. | high |
| 22 | [D-49](#d-49) Validate only changed fields on account update | Should account update validate only the fields the user changed, or every field (with migrated data cleaned first)? | Validate changed fields only; otherwise the screen does not work on real data. | high |
| 23 | [D-57](#d-57) How batch failures are signalled and handled | When a batch job fails or rejects records, what exit status does it give, does it stop the chain, and is it retried? | Fix with that policy; blind reruns and silent successes are the main operational risks. | high |
| 24 | [D-59](#d-59) Transaction ID scheme | How are transaction IDs generated, and does the key stay the 16-character text ID? | A shared sequence that keeps the 16-digit format, no seed record; add a surrogate key only if the data model team wants it. | high |
| 25 | [D-75](#d-75) Fix obvious defects in bulk | Approve fixing the defects listed in Appendix A, which no user would want to keep and whose fix does not change business results? | Fix all; review Appendix A for any exception. | medium |

## Counts

| What | Count |
| :-- | --: |
| Items extracted | 825 |
| of which open questions (`Q-`) | 307 |
| of which `DEF` items (defect-decide or `[security]`) | 380 |
| of which other items marked defect-decide (`BR`, `VAL`, `FLOW`, `SE`, `ERR`, `NUM`) | 138 |
| Items marked defect-decide / tagged `[security]` (overlapping) | 502 / 88 |
| From branch `modernization` / from branch `nl/G` (job and schedule specs) | 617 / 208 |
| (a) product or business decision | 460 |
| (b) security decision | 150 |
| (c) mainframe-only or platform question, no decision needed | 214 |
| (d) already answered in the spec | 1 |
| Decisions after merging (a) and (b) | 75 (34 high, 24 medium, 17 low priority) |
| of which on the short list | 25 |
| Items covered by the bulk fix D-75 (Appendix A) | 79 |

## All decisions

| ID | Theme | Decision | Recommendation | Priority | Items |
| :-- | :-- | :-- | :-- | :-- | --: |
| [D-01](#d-01) | S1 | Require sign-on for every screen and API | Fix. | high | 26 |
| [D-02](#d-02) | S1 | Enforce roles on every function | Fix with the two existing roles, deny unknown types. | high | 23 |
| [D-03](#d-03) | S1 | Who may see and change which customers' data | Keep the staff model for parity, combined with sign-on, roles, masking and access audit. | high | 22 |
| [D-04](#d-04) | S1 | Password storage and case rules | Salted hashes (bcrypt or Argon2), case-sensitive passwords, case-insensitive IDs, hash once at migration and force a reset. | high | 11 |
| [D-05](#d-05) | S1 | Attempt limits, lockout, timeout, sign-off | Fix with the example values unless your policy sets others. | high | 5 |
| [D-06](#d-06) | S1 | First administrator, seed users and keeping admin access | Fix. | medium | 4 |
| [D-07](#d-07) | S1 | Least privilege for services, jobs and the database | Fix. | high | 9 |
| [D-08](#d-08) | S1 | Direct editing of balances on account update | Fix. | high | 2 |
| [D-09](#d-09) | S2 | Mask sensitive data on screens | Fix as described. | high | 10 |
| [D-10](#d-10) | S2 | Sensitive data in reports, logs and files | Fix. | high | 32 |
| [D-11](#d-11) | S3 | Account update writes the group ID into the ZIP code | Fix and migrate the group ID. | high | 9 |
| [D-12](#d-12) | S3 | Card update overwrites the CVV with 000 | Fix. | high | 5 |
| [D-13](#d-13) | S3 | Card update can move a card to another account | Fix with a read-only account. | high | 6 |
| [D-14](#d-14) | S3 | ZIP+4 and long phone numbers are cut | Fix. | medium | 5 |
| [D-15](#d-15) | S3 | User update and delete act on the wrong user | Fix. | medium | 4 |
| [D-16](#d-16) | S4 | Save account and customer together | Fix. | high | 6 |
| [D-17](#d-17) | S4 | Detect concurrent changes (optimistic locking) | Fix uniformly with version columns. | medium | 9 |
| [D-18](#d-18) | S4 | Batch window: online access while batch runs | No outage with snapshot reads, if D-17 is accepted. | high | 3 |
| [D-19](#d-19) | S5 | Over-limit rule in posting | Keep for the first release so posting matches the reference run, then change it as a separate business decision. | high | 3 |
| [D-20](#d-20) | S5 | Posting when the account update fails | Roll back and reject. | high | 3 |
| [D-21](#d-21) | S5 | Record every reject reason | Store all reasons alongside the primary one. | low | 1 |
| [D-22](#d-22) | S5 | Posting transaction boundary | Per-record atomicity inside restartable chunks (Spring Batch). | high | 1 |
| [D-23](#d-23) | S5 | Prevent double posting and double interest | Fix. | high | 3 |
| [D-24](#d-24) | S5 | How often interest is charged | Monthly on a chosen day. | high | 3 |
| [D-25](#d-25) | S5 | Interest on credit balances and rounding | Keep truncation for parity. | medium | 4 |
| [D-26](#d-26) | S5 | Fees in the interest job | Keep no fees until fee rules are specified. | low | 2 |
| [D-27](#d-27) | S5 | Transactions added online are never posted | Feed the posting input. | high | 3 |
| [D-28](#d-28) | S6 | Payments update cycle totals and category balances | Fix through the same posting service as D-27, after checking the effect on interest results. | high | 2 |
| [D-29](#d-29) | S6 | Payment errors leave partial updates | Fix, unless operations name a reconciliation that relies on the partial updates. | high | 2 |
| [D-30](#d-30) | S6 | Payment amount and confirmation | Keep full-balance payment and lock the account on confirmation. | medium | 2 |
| [D-31](#d-31) | S7 | Authorization decisions use another request's data | Fix. | high | 9 |
| [D-32](#d-32) | S7 | Which checks can decline an authorization | Fix. | high | 6 |
| [D-33](#d-33) | S7 | Available credit and its release | Fix. | high | 6 |
| [D-34](#d-34) | S7 | Authorization purge: retention and delete rule | Fix with the 5-day default unless you have a policy. | high | 11 |
| [D-35](#d-35) | S7 | Reply only after the database commit | Fix. | high | 3 |
| [D-36](#d-36) | S7 | Fraud record key | Fix. | medium | 3 |
| [D-37](#d-37) | S8 | Which batch cycle the scheduler runs | Option F. | high | 13 |
| [D-38](#d-38) | S8 | When statements are produced | After COMBTRAN, once per statement period (see D-48). | medium | 2 |
| [D-39](#d-39) | S8 | Where the authorization purge runs | After the cycle as its own task that never gates posting. | medium | 3 |
| [D-40](#d-40) | S8 | How much history to keep | Retention by data class in days, set by your data-handling policy. | medium | 5 |
| [D-41](#d-41) | S8 | Demo reloads of master data in production | Seed-only. | high | 6 |
| [D-42](#d-42) | S8 | Which utility and demo jobs to keep | Drop and replace as in the second option, unless operations name a user. | low | 21 |
| [D-43](#d-43) | S9 | What a statement covers | Fix. | high | 5 |
| [D-44](#d-44) | S9 | Names and addresses cut on statements | Fix. | medium | 6 |
| [D-45](#d-45) | S9 | Statement output format | One escaped PDF per statement from a template. | low | 5 |
| [D-46](#d-46) | S9 | Transaction report date filter and early stop | Fix. | high | 5 |
| [D-47](#d-47) | S9 | Report totals per account or per card | Per account, as the label promises. | low | 3 |
| [D-48](#d-48) | S9 | Report request delivery and status | Asynchronous run with status and download. | medium | 4 |
| [D-49](#d-49) | S10 | Validate only changed fields on account update | Validate changed fields only. | high | 2 |
| [D-50](#d-50) | S10 | Account update field rules | Fix as in the second option. | medium | 21 |
| [D-51](#d-51) | S10 | Card update field rules | Fix expiry validation. | medium | 7 |
| [D-52](#d-52) | S10 | Transaction add and report request input checks | Fix. | medium | 12 |
| [D-53](#d-53) | S10 | User administration input rules | Fix. | medium | 5 |
| [D-54](#d-54) | S11 | List paging behaviour | Fix. | low | 20 |
| [D-55](#d-55) | S11 | Selection marks survive paging | Fix. | medium | 6 |
| [D-56](#d-56) | S12 | Audit trail | Audit the listed events. | medium | 2 |
| [D-57](#d-57) | S13 | How batch failures are signalled and handled | Fix with that policy. | high | 19 |
| [D-58](#d-58) | S13 | Technical codes in user messages | Fix. | low | 4 |
| [D-59](#d-59) | S14 | Transaction ID scheme | A shared sequence that keeps the 16-digit format, no seed record. | high | 11 |
| [D-60](#d-60) | S14 | One timestamp format | One typed column. | medium | 4 |
| [D-61](#d-61) | S14 | Accounts with several cards, or none | Fix. | medium | 11 |
| [D-62](#d-62) | S14 | Export and import (branch migration) | Replace with a database export/load unless an external receiver of the file is named. | low | 12 |
| [D-63](#d-63) | S15 | One store for transaction types and categories | One store. | medium | 11 |
| [D-64](#d-64) | S15 | Deleting a type that has categories | Keep refusing with a clear message. | low | 3 |
| [D-65](#d-65) | S15 | Transaction type description rules | Fix. | low | 13 |
| [D-66](#d-66) | S15 | Batch transaction type maintenance | Drop it unless a producer of the input is named. | low | 1 |
| [D-67](#d-67) | S15 | Who maintains interest rates | Controlled maintenance with the `DEFAULT` check. | medium | 1 |
| [D-68](#d-68) | S16 | Save and exit semantics | Fix. | low | 4 |
| [D-69](#d-69) | S16 | Invalid keys | Fix. | low | 5 |
| [D-70](#d-70) | S16 | Options for modules that are not installed | Fix. | low | 2 |
| [D-71](#d-71) | S16 | Full transaction details on view | Fix. | low | 2 |
| [D-72](#d-72) | S17 | Inquiry message contract | Structured reply with a status, after confirming no requester parses the text. | low | 9 |
| [D-73](#d-73) | S17 | Reply routing and correlation | Fix. | medium | 5 |
| [D-74](#d-74) | S17 | Inquiry errors | Fix. | low | 3 |
| [D-75](#d-75) | S18 | Fix obvious defects in bulk | Fix all. | medium | 79 |

## S1. Sign-on, roles and access control

### D-01

**Require sign-on for every screen and API** · priority high · 26 items (26 security)

- **Question:** Must every screen and service require an authenticated session, with the user's identity and role taken only from that session?
- **As-is:** Identity is two commarea fields that any program can set; no program verifies it (INV-006). Typing a card-list, account, card or transaction-type transaction code and pressing PF3 opens the main or admin menu without a password (DEF-SGN-012, DEF-MENU-003, DEF-CRDL-003, DEF-TTLS-017, DEF-AUSM-012). Screens overwrite the user type on exit (PF3 forces `U` or `A`: DEF-ACUP-009, DEF-CRDL-004, DEF-TTUP-010), and account update saves keys and values taken from protected screen fields (DEF-ACUP-013).
- **Options:**
  1. Keep as-is (not viable on the web: any client could claim any identity).
  2. Fix: server-side authenticated session for every screen and API; identity and role come only from the session; no screen can change them; saves re-read keys from the server and re-validate every field.
- **Recommendation:** Fix. Today the menus are reachable without a password, and a web client can send any field.
- **Affects:** Spring Security configuration, every controller and React route, session design; acceptance tests must assert that each bypass path is refused.
- **Items:** DEF-ACUP-009, DEF-ACUP-013, Q-ACUP-006, DEF-ACVW-009, DEF-ACVW-011, DEF-AUDT-009, Q-AUDT-005, DEF-AUSM-012, Q-AUSM-005, DEF-CRDL-003, DEF-CRDL-004, DEF-CRDL-005, FLOW-CRDL-001, FLOW-CRDL-008, SE-CRDL-003, Q-CRDL-004, Q-CRDL-008, DEF-CRDU-007, DEF-CRDV-003, DEF-CRDV-004, Q-CRDV-003, DEF-MENU-003, DEF-SGN-006, DEF-SGN-012, DEF-TTLS-017, DEF-TTUP-017

### D-02

**Enforce roles on every function** · priority high · 23 items (23 security)

- **Question:** Which roles exist, which functions does each role get, and what happens to a user whose type is not a known role?
- **As-is:** Roles are `A` (admin) and anything else (regular). After sign-on nothing checks the role: the admin menu, user administration, transaction-type maintenance and transaction add open to whoever reaches them (DEF-ADMN-001, DEF-USRL-008, DEF-TTUP-010, DEF-TRNA-012); the main menu marks every option for regular users (DEF-MENU-002); a type other than `A`/`U` (blank, lower-case `a`) signs on as a regular user (DEF-SGN-005); anyone can mark or clear fraud and no user is recorded (DEF-AUDT-006).
- **Options:**
  1. Keep: menus are the only control.
  2. Fix with the two existing roles: admin and regular user, checked on the server for every function; unknown types are refused.
  3. Fix with finer roles (for example admin, operator, fraud analyst, regular user).
- **Recommendation:** Fix with the two existing roles, deny unknown types; add a fraud role only if the authorization module is built. It closes the gap with the least new design.
- **Affects:** Role model, Spring Security method/URL rules, admin and maintenance APIs, user type column constraint.
- **Items:** DEF-ADMN-001, Q-ADMN-001, DEF-AUDT-006, Q-AUDT-003, DEF-JCSD-003, Q-JCSD-001, DEF-JCSDD-001, BR-MENU-003, DEF-MENU-002, Q-MENU-001, DEF-SGN-005, Q-SGN-005, DEF-TRNA-012, Q-TRNA-002, DEF-TTBU-009, DEF-TTLS-009, Q-TTLS-003, DEF-TTUP-010, Q-TTUP-006, DEF-USRA-005, DEF-USRL-008, DEF-USRL-013, DEF-USRU-006

### D-03

**Who may see and change which customers' data** · priority high · 22 items (22 security)

- **Question:** Are regular users bank staff who may work on any customer, or cardholders who may see only their own accounts and cards?
- **As-is:** Every user lists every card and transaction, views and updates any account and customer, pays any account's bill, requests reports over all transactions and views any authorization (DEF-CRDL-001, DEF-TRNL-007, DEF-ACVW-008, DEF-ACUP-001, DEF-BILL-009, DEF-RPTS-007, DEF-AUSM-007). The user record has no link to a customer or account (`ENT-USER-SECURITY`), so 'own data' cannot be expressed today.
- **Options:**
  1. Keep: staff model, every signed-on user may work on every customer (with D-01, D-02, D-08 and audit).
  2. Fix: cardholder model, regular users limited to their own accounts and cards; needs a new user-to-customer link and changes every list and lookup.
  3. Mixed: staff roles see all, a separate cardholder portal sees own data.
- **Recommendation:** Keep the staff model for parity, combined with sign-on, roles, masking and access audit; choose the cardholder model only if cardholders are users, because it needs a new data link.
- **Affects:** Authorization filter in every account, card, transaction, payment, report and authorization service; possibly a new user-customer table.
- **Items:** DEF-ACUP-001, Q-ACUP-004, DEF-ACVW-008, Q-ACVW-001, DEF-AUSM-007, Q-AUSM-002, DEF-BILL-009, Q-BILL-004, BR-CRDL-002, DEF-CRDL-001, Q-CRDL-001, DEF-CRDU-006, Q-CRDU-005, DEF-CRDV-002, Q-CRDV-002, DEF-JCSD2-001, DEF-RPTS-007, Q-RPTS-004, DEF-TRNA-009, BR-TRNL-006, DEF-TRNL-007, Q-TRNL-001

### D-04

**Password storage and case rules** · priority high · 11 items (11 security)

- **Question:** How are passwords stored and migrated, and are passwords and user IDs case-sensitive?
- **As-is:** Passwords are plain text in the user file and compared with the upper-cased typed value (DEF-SGN-001, BR-SGN-003). User add and update store values as typed, so a user created with lower-case letters can never sign on (DEF-SGN-011, INV-007, DEF-USRA-001). User update shows the current password (DEF-USRU-005). The reload job holds every password in clear text (DEF-JUSEC-001).
- **Options:**
  1. Keep plain text (not acceptable).
  2. Hash, keep case-insensitive: upper-case before hashing; existing passwords hash as they are.
  3. Hash, case-sensitive passwords and case-insensitive IDs; migrate by hashing once and force a reset at first sign-on.
  4. Delegate to an external identity provider.
- **Recommendation:** Salted hashes (bcrypt or Argon2), case-sensitive passwords, case-insensitive IDs, hash once at migration and force a reset; the current passwords have been readable in clear text.
- **Affects:** Identity service, user table (hash column), migration script, user add/update screens (no password display).
- **Items:** DEF-JUSEC-001, BR-SGN-003, DEF-SGN-001, DEF-SGN-011, Q-SGN-001, Q-SGN-002, DEF-USRA-001, DEF-USRA-003, DEF-USRU-005, DEF-USRU-011, Q-USRU-002

### D-05

**Attempt limits, lockout, timeout, sign-off** · priority high · 5 items (4 security)

- **Question:** What attempt limit, lockout, idle timeout and sign-off does the target need, and should sign-on failures use one neutral message?
- **As-is:** Unlimited password attempts, nothing logged (DEF-SGN-002); distinct `User not found` and `Wrong Password` messages reveal valid IDs (DEF-SGN-003); no session timeout; PF3 on the main menu returns to sign-on without ending anything (Q-MENU-003).
- **Options:**
  1. Keep as-is.
  2. Fix with values you choose, for example 5 failures lock for 15 minutes, 15-minute idle timeout, explicit sign-off, one neutral message, sign-on audit records.
- **Recommendation:** Fix with the example values unless your policy sets others; unlimited guessing is the main gap.
- **Affects:** Identity service, session configuration, sign-on screen messages, audit table.
- **Items:** Q-MENU-003, DEF-SGN-002, DEF-SGN-003, Q-SGN-003, Q-SGN-004

### D-06

**First administrator, seed users and keeping admin access** · priority medium · 4 items (3 security)

- **Question:** How is the first administrator created, are demo users seeded, and may an admin delete themselves or the last admin?
- **As-is:** DUSRSECJ loads 10 users with the same public password and a reload replaces every user, losing users added online (Q-JUSEC-001, Q-JUSEC-002). An admin may delete their own user or the last admin; only a batch reload restores access (DEF-USRD-004, INV-009).
- **Options:**
  1. Keep: seed job and unrestricted delete.
  2. Fix: one-time bootstrap admin with forced password change (or the identity provider); demo users only in demo environments; refuse deleting yourself or the last admin; no full user reload.
- **Recommendation:** Fix. Shared known passwords and a lockable admin function are avoidable risks.
- **Affects:** Deployment/provisioning, user admin delete rule, environment seed data.
- **Items:** Q-JUSEC-001, Q-JUSEC-002, DEF-USRD-004, Q-USRD-002

### D-07

**Least privilege for services, jobs and the database** · priority high · 9 items (9 security)

- **Question:** Should every service, job and integration client run with only the rights it needs, and must callers of the inquiry services authenticate?
- **As-is:** No CICS resource or command security, every file fully updatable by every program (DEF-JCSD-004, DEF-JCSD2-001); the Db2 set-up grants DBADM and all privileges to `PUBLIC` (DEF-JCRDB-003); online report jobs run with the region's identity (DEF-JTRPTP-005); FTP sends a clear-text password (DEF-JFTP-004); seed passwords are copied into sample datasets (DEF-JESRR-001); anyone who can put a message reads any account over MQ (DEF-MQAC-004, DEF-JCSDM-003).
- **Options:**
  1. Keep broad access.
  2. Fix: per-service database logins and roles, report jobs under the requesting user, authenticated RabbitMQ clients with per-client permissions, no FTP or clear-text secrets.
- **Recommendation:** Fix; it is standard for the target platform and has no business trade-off.
- **Affects:** SQL Server logins, roles and grants; RabbitMQ users and vhost permissions; batch job identity; secrets management.
- **Items:** DEF-JCRDB-003, Q-JCRDB-003, DEF-JCSD-004, DEF-JCSDM-003, DEF-JESRR-001, DEF-JFTP-004, DEF-JTRPTP-005, DEF-MQAC-004, Q-MQAC-003

### D-08

**Direct editing of balances on account update** · priority high · 2 items (2 security)

- **Question:** May the account update screen change the current balance and cycle credit and debit totals directly?
- **As-is:** Anyone who reaches account update can set the balance and cycle totals to any value, with no transaction, role check or audit (DEF-ACUP-114, Q-ACUP-103).
- **Options:**
  1. Keep editable (parity).
  2. Fix: read-only; balances change only through posted transactions or a privileged, audited adjustment.
- **Recommendation:** Fix. A direct edit bypasses the ledger, so balances and transactions stop agreeing.
- **Affects:** Account update screen and service, adjustment function (if wanted), audit.
- **Items:** DEF-ACUP-114, Q-ACUP-103

## S2. Sensitive data and credentials

### D-09

**Mask sensitive data on screens** · priority high · 10 items (10 security)

- **Question:** Which sensitive fields are masked on screens, who may reveal them, and may the CVV be held in session state?
- **As-is:** Account view and update show full SSN, government ID, date of birth and EFT account (DEF-ACVW-010, DEF-ACUP-016); card list, card view and transaction view show full 16-digit card numbers (DEF-CRDL-002, DEF-CRDV-011, DEF-TRNV-004); card update keeps CVV and card number in clear in the session data (DEF-CRDU-019).
- **Options:**
  1. Keep full display.
  2. Fix: mask by default (SSN and card number to the last 4 digits), reveal only for a permitted role and log the reveal; never keep the CVV in client or session state.
- **Recommendation:** Fix as described; full display serves no screen function and is a compliance exposure.
- **Affects:** React components, API response models (masked fields), role check and access log.
- **Items:** DEF-ACUP-016, DEF-ACVW-010, Q-ACVW-002, DEF-CRDL-002, Q-CRDL-002, DEF-CRDU-019, DEF-CRDV-011, Q-CRDV-006, DEF-TRNV-004, Q-TRNV-001

### D-10

**Sensitive data in reports, logs and files** · priority high · 32 items (32 security)

- **Question:** What may batch reports, job logs, extracts and export files contain?
- **As-is:** Reader jobs print every card with full number and CVV, every customer with SSN (DEF-CDRD-001, DEF-CURD-001, DEF-XRRD-001, DEF-JRCARD-001); posting and validation log card numbers (DEF-POST-011, DEF-TVAL-002); the transaction report prints full card numbers (DEF-TRPT-006, DEF-JTRPT-004); statements show the FICO score (DEF-STMT-008); export and import files hold everything in clear (DEF-EXPT-009, DEF-IMPT-008, DEF-JEXPT-002); the purge logs account numbers (DEF-AUPG-008); backup generations are kept beyond policy (DEF-JGDGB-002).
- **Options:**
  1. Keep.
  2. Fix: card numbers masked to the last 4 in every report and log; never output CVV, SSN or government ID; drop full-record dumps; encrypt export files and backups; remove the FICO score from statements.
- **Recommendation:** Fix; logs and reports are the usual leak path, and nothing downstream needs the full values.
- **Affects:** Report templates, logging configuration (masking), export/import format, backup policy, statement layout.
- **Items:** DEF-AUPG-008, Q-AUPG-009, DEF-AUTH-011, DEF-CDRD-001, Q-CDRD-001, DEF-CURD-001, Q-CURD-001, DEF-EXPT-009, DEF-EXPT-010, Q-EXPT-003, DEF-IMPT-008, DEF-INTC-011, DEF-JEXPT-002, Q-JEXPT-002, DEF-JGDGB-002, DEF-JIMPT-002, DEF-JPDF-003, DEF-JRACCT-002, DEF-JRCARD-001, DEF-JRCUST-001, DEF-JREJS-003, DEF-JRXREF-001, DEF-JTRPT-004, DEF-JTRPTP-004, DEF-POST-011, DEF-STIO-005, DEF-STMT-008, Q-STMT-004, DEF-TRPT-006, DEF-TVAL-002, DEF-XRRD-001, Q-XRRD-001

## S3. Data corruption defects

### D-11

**Account update writes the group ID into the ZIP code** · priority high · 9 items

- **Question:** Should the target store ZIP and disclosure group ID in their own fields, and how are the already-shifted sample accounts migrated?
- **As-is:** Account update writes the group ID at the ZIP position and undefined bytes over the real group ID (DEF-ACUP-002, DEF-ACUP-020, INV-039). All 50 sample accounts already hold `A000000000` in ZIP and a blank group ID (Q-ENT-001), so interest always falls back to the `DEFAULT` rates (Q-INTC-001, DEF-INTC-006).
- **Options:**
  1. Keep the shifted layout (reproduces the defect).
  2. Fix the layout and migrate: move `A000000000` into the group ID, leave ZIP empty; interest results change only for accounts whose group has specific rates.
  3. Fix the layout but migrate the data unchanged (group stays blank, interest still uses DEFAULT).
- **Recommendation:** Fix and migrate the group ID; the defect silently changes which interest rates apply.
- **Affects:** Account table schema, data migration of `ACCTDAT`, account update save, interest parity tests.
- **Items:** BR-ACUP-006, DEF-ACUP-002, DEF-ACUP-020, NUM-ACUP-010, SE-ACUP-006, Q-ACUP-001, Q-ACUP-010, Q-ENT-001, Q-INTC-001

### D-12

**Card update overwrites the CVV with 000** · priority high · 5 items

- **Question:** Should a card save change only the fields the user edits?
- **As-is:** Every card update save rewrites the CVV as `000` and fills the unused bytes with unrelated session bytes (DEF-CRDU-002, DEF-CRDU-003, INV-036).
- **Options:**
  1. Keep (reproduces the defect).
  2. Fix: update only name, expiry and status; keep the stored CVV.
- **Recommendation:** Fix; the as-is save destroys the CVV.
- **Affects:** Card update service (partial update), card table.
- **Items:** BR-CRDU-006, DEF-CRDU-002, DEF-CRDU-003, NUM-CRDU-004, Q-CRDU-002

### D-13

**Card update can move a card to another account** · priority high · 6 items

- **Question:** Must the account on card view and card update be the card's own account, or may card update move a card to another account?
- **As-is:** Card view shows any card for any account typed (DEF-CRDV-001). On the menu path card update writes the typed account into the card while the cross-reference keeps the old one, so the two stores disagree (DEF-CRDU-001, BR-CRDU-007, INV-030).
- **Options:**
  1. Keep.
  2. Fix: card must belong to the account; account is read-only in card update.
  3. Fix: allow moving a card, updating card and cross-reference together.
- **Recommendation:** Fix with a read-only account; moving cards is a new feature nobody has asked for.
- **Affects:** Card view/update services and screens; card-to-account relation in the schema.
- **Items:** BR-CRDU-002, BR-CRDU-007, DEF-CRDU-001, Q-CRDU-001, DEF-CRDV-001, Q-CRDV-001

### D-14

**ZIP+4 and long phone numbers are cut** · priority medium · 5 items

- **Question:** Should the target show and store the full 10-character ZIP and 15-character phone numbers?
- **As-is:** Account update shows and saves only 5 ZIP characters, so every save drops the +4 part; account view cuts ZIP to 5 and phones to 13 (DEF-ACUP-003, INV-044, NUM-ACVW-004).
- **Options:**
  1. Keep truncation.
  2. Fix: full ZIP and phone on screen and in storage.
- **Recommendation:** Fix; every save loses customer data today.
- **Affects:** Customer columns, account view and update screens.
- **Items:** DEF-ACUP-003, NUM-ACUP-005, Q-ACUP-005, DEF-ACVW-017, NUM-ACVW-004

### D-15

**User update and delete act on the wrong user** · priority medium · 4 items

- **Question:** Should the user ID be locked after a fetch, and must a delete be confirmed on the displayed user?
- **As-is:** After fetching a user, changing the ID and saving writes the first user's names, password and type into another user (DEF-USRU-003). Typing an ID and PF5 deletes at once, with no confirmation (DEF-USRD-001, BR-USRD-003).
- **Options:**
  1. Keep.
  2. Fix: lock the ID after fetch; confirm the displayed user before delete.
- **Recommendation:** Fix; it overwrites or deletes the wrong user.
- **Affects:** User update and delete screens and services.
- **Items:** BR-USRD-003, DEF-USRD-001, Q-USRD-001, DEF-USRU-003

## S4. Concurrency, locking and transaction boundaries

### D-16

**Save account and customer together** · priority high · 6 items

- **Question:** Must an account update save the account and the customer in one transaction?
- **As-is:** The account is rewritten first; if the customer rewrite fails the account change stays while the user is told the update failed, and a customer lock failure is reported as committed (DEF-ACUP-005, ERR-ACUP-004, INV-043). The files are not recoverable (ERR-JCSD-001).
- **Options:**
  1. Keep.
  2. Fix: one database transaction per save; report failure only when nothing was saved.
- **Recommendation:** Fix; a half-saved update cannot be seen or corrected by the user.
- **Affects:** Account update service transaction boundary and outcome messages.
- **Items:** DEF-ACUP-005, ERR-ACUP-004, SE-ACUP-007, Q-ACUP-002, ERR-JCSD-001, Q-JCSD-002

### D-17

**Detect concurrent changes (optimistic locking)** · priority medium · 9 items

- **Question:** How should the target handle two users (or a user and a batch job) changing the same record?
- **As-is:** Account and card update compare fields under lock before saving (INV-037, INV-042), but on a conflict account update shows stale values and loses the user's edits (DEF-ACUP-006); user update is last-save-wins (DEF-USRU-007); the transaction-type list confirms only the row number, so a delete can run on a type the user never confirmed (DEF-TTLS-003, DEF-TTLS-014, DEF-TTUP-003).
- **Options:**
  1. Keep per-screen behaviour.
  2. Fix: a version column on every updatable table; a stale save is refused, current values are reloaded and the user's edits kept for review; confirmations bind action, row and key.
- **Recommendation:** Fix uniformly with version columns; it maps the existing field comparisons onto one mechanism.
- **Affects:** Every update service, table schemas (version column), confirmation flows.
- **Items:** DEF-ACUP-006, Q-ACUP-003, DEF-TTLS-003, DEF-TTLS-014, Q-TTLS-007, DEF-TTUP-003, Q-TTUP-001, DEF-USRU-007, Q-USRU-003

### D-18

**Batch window: online access while batch runs** · priority high · 3 items

- **Question:** Must online functions stop while the batch cycle runs, or may they run alongside?
- **As-is:** CLOSEFIL closes five CICS files for the cycle, so sign-on, user admin and account functions are unavailable (INV-002); three files are never closed, so statements and the report read data that online users may be changing (DEF-JCLOSE-003, DEF-JTRPT-005).
- **Options:**
  1. Keep a batch window: online read-only or closed during the cycle.
  2. Fix: no outage; short database transactions, version checks (D-17) and snapshot reads for statements, reports and export.
  3. Hybrid: window only for posting and interest.
- **Recommendation:** No outage with snapshot reads, if D-17 is accepted; otherwise keep the window. It removes a daily outage.
- **Affects:** Batch isolation level, online availability, DAG design, account update conflict handling.
- **Items:** DEF-JCLOSE-003, Q-JCLOSE-001, DEF-JTRPT-005

## S5. Interest and posting rules

### D-19

**Over-limit rule in posting** · priority high · 3 items

- **Question:** Keep the over-limit formula (cycle credit minus cycle debit plus amount) or switch to a balance-based check?
- **As-is:** Posting rejects with reason 102 when cycle credit minus cycle debit plus the amount exceeds the credit limit; payments and refunds raise the figure and the current balance is ignored (BR-POST-001, DEF-POST-005, Q-POST-001).
- **Options:**
  1. Keep (reproduces the reference rejects on the 300 sample transactions).
  2. Fix: current balance plus amount greater than the credit limit; changes which transactions are rejected.
- **Recommendation:** Keep for the first release so posting matches the reference run, then change it as a separate business decision.
- **Affects:** Posting validation step, reject parity tests.
- **Items:** BR-POST-001, DEF-POST-005, Q-POST-001

### D-20

**Posting when the account update fails** · priority high · 3 items

- **Question:** What should posting do when it cannot update the account?
- **As-is:** On a failed account rewrite posting sets reason 109 but still updates the category balance and writes the transaction, with no reject and no abend; other rewrite errors are ignored (DEF-POST-003, DEF-POST-012, Q-POST-003).
- **Options:**
  1. Keep the silent partial update.
  2. Fix: roll back the record and reject it.
  3. Fix: stop the job.
- **Recommendation:** Roll back and reject; balances must not disagree with transactions.
- **Affects:** Posting writer and transaction boundary, reject table.
- **Items:** DEF-POST-003, DEF-POST-012, Q-POST-003

### D-21

**Record every reject reason** · priority low · 1 items

- **Question:** Should a rejected transaction record every failing reason, or only one as today?
- **As-is:** Posting keeps one reason per transaction; a later check overwrites an earlier one (Q-POST-002).
- **Options:**
  1. Keep one reason.
  2. Fix: keep the as-is primary reason and also store all failing reasons.
- **Recommendation:** Store all reasons alongside the primary one; it helps operations and keeps parity on the primary code.
- **Affects:** Reject table, posting validator.
- **Items:** Q-POST-002

### D-22

**Posting transaction boundary** · priority high · 1 items

- **Question:** What is the unit of work for posting: one record, a chunk with restart, or the whole file?
- **As-is:** No boundary: VSAM batch has no rollback, so an abend mid-run leaves earlier records posted and the failing record partly applied (Q-POST-004).
- **Options:**
  1. Per record.
  2. Per chunk with restart, each record atomic.
  3. Per file (all or nothing).
- **Recommendation:** Per-record atomicity inside restartable chunks (Spring Batch); no record is half posted and a failed run restarts.
- **Affects:** Posting Spring Batch step configuration, restart metadata.
- **Items:** Q-POST-004

### D-23

**Prevent double posting and double interest** · priority high · 3 items

- **Question:** Must the target refuse to post the same daily file twice or charge interest twice in one cycle?
- **As-is:** Nothing marks a daily file as posted, so a resubmission after a reset posts it again (DEF-JPOST-003). A second interest run charges interest again and category balances are never reset (DEF-INTC-005, Q-INTC-005).
- **Options:**
  1. Keep.
  2. Fix: a run-control table; refuse a second posting of the same file and a second interest run for the same cycle; reset or age category balances after interest.
- **Recommendation:** Fix; reruns otherwise change customer balances.
- **Affects:** Run-control table, posting and interest steps, category balance handling.
- **Items:** DEF-INTC-005, Q-INTC-005, DEF-JPOST-003

### D-24

**How often interest is charged** · priority high · 3 items

- **Question:** Is interest calculated monthly or in every batch cycle?
- **As-is:** The formula is balance x rate / 1200, a monthly rate (BR-INTC-001). The README and full-batch script run it every cycle, so a daily cycle charges a month's interest each day; Control-M names a monthly folder that is never ordered (Q-JINTC-002, DEF-JCTLM-002).
- **Options:**
  1. Every cycle (README).
  2. Monthly on a chosen day (Control-M intent).
- **Recommendation:** Monthly on a chosen day; it matches the monthly rate.
- **Affects:** Interest DAG schedule.
- **Items:** DEF-JCTLM-002, Q-JCTLM-002, Q-JINTC-002

### D-25

**Interest on credit balances and rounding** · priority medium · 4 items

- **Question:** Do negative (credit) category balances earn negative interest, and is interest truncated or rounded?
- **As-is:** A non-zero rate on a negative category balance reduces the account balance (DEF-INTC-012, Q-INTC-003); interest is truncated toward zero to cents (BR-INTC-001, Q-INTC-004).
- **Options:**
  1. Keep both.
  2. Fix credit interest (charge positive balances only), keep truncation.
  3. Also change to rounding.
- **Recommendation:** Keep truncation for parity; charge positive balances only unless the product pays interest on credits.
- **Affects:** Interest calculation service, interest parity tests.
- **Items:** BR-INTC-001, DEF-INTC-012, Q-INTC-003, Q-INTC-004

### D-26

**Fees in the interest job** · priority low · 2 items

- **Question:** Should the interest job charge fees?
- **As-is:** The fee step is empty; no fees are ever charged (DEF-INTC-004, Q-INTC-007).
- **Options:**
  1. Keep: no fees.
  2. Add fee rules (needs a business specification).
- **Recommendation:** Keep no fees until fee rules are specified.
- **Affects:** Interest step, fee transaction types.
- **Items:** DEF-INTC-004, Q-INTC-007

### D-27

**Transactions added online are never posted** · priority high · 3 items

- **Question:** Should a transaction added online change the account balance, and through which path?
- **As-is:** Transaction add writes only the transaction record: no balance, cycle or category update and no limit, status or expiry check (DEF-TRNA-001, BR-TRNA-007, INV-004).
- **Options:**
  1. Keep: record only.
  2. Post immediately with the posting rules.
  3. Feed the next posting run's input so the batch rules apply once.
- **Recommendation:** Feed the posting input; it keeps one posting path and one set of rules.
- **Affects:** Transaction add service, posting input, reports and statements.
- **Items:** BR-TRNA-007, DEF-TRNA-001, Q-TRNA-001

## S6. Bill payment rules

### D-28

**Payments update cycle totals and category balances** · priority high · 2 items

- **Question:** Should an online payment update the cycle credit total and category balance like posting does, and with which sign?
- **As-is:** A payment reduces only the current balance, stores a positive amount (posting treats positive as a charge), and does not reach cycle totals or category balances, so interest and statements ignore it (DEF-BILL-010, Q-BILL-002, INV-004, INV-005).
- **Options:**
  1. Keep.
  2. Fix: post payments like other transactions (negative amount, cycle credit, category balance).
- **Recommendation:** Fix through the same posting service as D-27, after checking the effect on interest results; payments should count toward interest and statements.
- **Affects:** Bill payment service, posting service, interest and statement parity.
- **Items:** DEF-BILL-010, Q-BILL-002

### D-29

**Payment errors leave partial updates** · priority high · 2 items

- **Question:** Should a payment be all-or-nothing and stop at the first error?
- **As-is:** After confirmation the steps continue after an error: a payment can zero the balance with no payment record, or be written with the wrong card number (DEF-BILL-001, Q-BILL-003).
- **Options:**
  1. Keep.
  2. Fix: one transaction for record and balance; stop at the first error.
- **Recommendation:** Fix, unless operations name a reconciliation that relies on the partial updates.
- **Affects:** Bill payment service transaction.
- **Items:** DEF-BILL-001, Q-BILL-003

### D-30

**Payment amount and confirmation** · priority medium · 2 items

- **Question:** Keep 'pay the full balance only', or add an amount field? Must the account be locked on the confirmation step?
- **As-is:** The user can only pay the full current balance (BR-BILL-007, Q-BILL-001). On the confirmation screen the account ID stays editable, so a user can pay another account in full without seeing its balance (DEF-BILL-014).
- **Options:**
  1. Keep full-balance payment, fix the editable account.
  2. Add a partial amount (new feature).
  3. Keep everything as-is.
- **Recommendation:** Keep full-balance payment and lock the account on confirmation; a partial amount is a feature request.
- **Affects:** Bill payment screen and service.
- **Items:** DEF-BILL-014, Q-BILL-001

## S7. Authorizations and available credit (optional IMS/Db2/MQ module)

### D-31

**Authorization decisions use another request's data** · priority high · 9 items

- **Question:** Fix the authorization service so each request is decided and answered only on its own data?
- **As-is:** State is not reset between requests: an unknown card can be approved against the previous account's summary, a missing account uses the previous limits, the declined amount can be an earlier request's (DEF-AUTH-002, DEF-AUTH-003, DEF-AUTH-004); the reply buffer grows, so later replies carry earlier decisions and other card numbers (DEF-AUTH-001, INV-051).
- **Options:**
  1. Keep (reproduces approvals on wrong data).
  2. Fix: per-request state; exactly one reply per request, correlated by ID.
- **Recommendation:** Fix; approvals on another account's data are a money and privacy defect.
- **Affects:** Authorization service (RabbitMQ consumer), reply contract.
- **Items:** BR-AUTH-003, BR-AUTH-009, DEF-AUTH-001, DEF-AUTH-002, DEF-AUTH-003, DEF-AUTH-004, DEF-AUTH-015, NUM-AUTH-003, Q-AUTH-001

### D-32

**Which checks can decline an authorization** · priority high · 6 items

- **Question:** Should the service check card status and expiry, account status, fraud and amount validity, and what is the authorization code?
- **As-is:** Only 'card found' and 'amount fits available credit' are checked; reason codes 4200-5200 and 9000 are unreachable (DEF-AUTH-006, BR-AUTH-007); zero, negative or non-numeric amounts are approved and a negative amount raises available credit (DEF-AUTH-007); the authorization code is the request's time field (BR-AUTH-008).
- **Options:**
  1. Keep.
  2. Fix: implement the checks with the existing reason codes, reject invalid amounts, generate a unique code.
- **Recommendation:** Fix; the reason codes already exist and a negative amount should never raise credit.
- **Affects:** Authorization decision rules, reason codes, reply.
- **Items:** BR-AUTH-007, BR-AUTH-008, DEF-AUTH-006, DEF-AUTH-007, Q-AUTH-002, Q-AUTH-004

### D-33

**Available credit and its release** · priority high · 6 items

- **Question:** How is available credit computed, and are expired approvals released?
- **As-is:** Once a summary exists, available credit is the limit minus the running sum of approvals, ignoring posted transactions and payments; otherwise it is limit minus balance (DEF-AUTH-005, BR-AUTH-004). The purge adjusts totals only in memory, so credit is never restored (DEF-AUPG-003, BR-AUPG-003, INV-047).
- **Options:**
  1. Keep.
  2. Fix: credit limit minus posted balance minus pending approvals; release approvals when purged or matched.
- **Recommendation:** Fix; as-is credit only ever shrinks.
- **Affects:** Authorization service, purge step, summary table.
- **Items:** BR-AUPG-003, DEF-AUPG-003, Q-AUPG-002, BR-AUTH-004, DEF-AUTH-005, Q-AUTH-003

### D-34

**Authorization purge: retention and delete rule** · priority high · 11 items

- **Question:** How long are authorizations kept, are fraud-flagged ones kept longer, and when is a summary deleted?
- **As-is:** The shipped expiry of 0 overrides the 5-day default, so every cycle deletes every authorization and summary (DEF-JAUPG-001, DEF-AUPG-001); fraud-flagged ones are purged like the rest (DEF-AUPG-009); the age is wrong across a year end (DEF-AUPG-004); a summary with no approvals is deleted with all details, declined ones included (DEF-AUPG-002).
- **Options:**
  1. Keep expiry 0.
  2. Fix: a real expiry (for example 5 days), fraud-flagged kept at least as long as the fraud record, correct age, delete a summary only when it has no details left.
- **Recommendation:** Fix with the 5-day default unless you have a policy; expiry 0 erases the data the screens show.
- **Affects:** Purge job parameters and rules, retention policy.
- **Items:** BR-AUPG-001, BR-AUPG-004, DEF-AUPG-001, DEF-AUPG-002, DEF-AUPG-004, DEF-AUPG-009, Q-AUPG-001, Q-AUPG-005, Q-AUPG-006, BR-JAUPG-001, DEF-JAUPG-001

### D-35

**Reply only after the database commit** · priority high · 3 items

- **Question:** Must the request be consumed, the database updated and the reply sent in one unit of work?
- **As-is:** The request is consumed and the reply sent before the summary and detail are written, with no rollback (DEF-AUTH-008, FLOW-AUTH-003).
- **Options:**
  1. Keep.
  2. Fix: reply only after commit; consume the message in the same unit of work.
- **Recommendation:** Fix; a requester can otherwise receive an approval that was never recorded.
- **Affects:** Authorization consumer transaction handling.
- **Items:** DEF-AUTH-008, FLOW-AUTH-003, Q-AUTH-005

### D-36

**Fraud record key** · priority medium · 3 items

- **Question:** Which key identifies an authorization in the fraud table?
- **As-is:** The key mixes the request date with the processing time, so two authorizations can share one fraud row and one overwrites the other (DEF-AUFR-001, NUM-AUFR-002).
- **Options:**
  1. Keep.
  2. Fix: use the authorization's own key (account, card, processing timestamp) or a surrogate ID.
- **Recommendation:** Fix; fraud marks must not collide.
- **Affects:** Fraud table key, fraud marking service.
- **Items:** DEF-AUFR-001, NUM-AUFR-002, Q-AUFR-001

## S8. Batch cycle and scheduler

### D-37

**Which batch cycle the scheduler runs** · priority high · 13 items

- **Question:** Which batch cycle do the Airflow DAGs implement (options A-F of `jobs/README.md` on nl/G)?
- **As-is:** Seven sources describe the cycle and no two agree; CA-7 has no interest cycle and loops (DEF-JCA7-005), Control-M has no posting and empties the transaction master daily (DEF-JCTLM-001); posting needs `TRANSACT` empty (DEF-JPOST-002, INV-003); WAITSTEP is a fixed pause instead of a check (Q-WAIT-002). Options: A README with demo reloads, B runbook, C split posting/interest scripts, D CA-7, E Control-M, F derived from the data dependencies (Q-JCA7-001, Q-JCTLM-001).
- **Options:**
  1. A: README (resets all data each cycle; demo only).
  2. C: split daily posting and periodic interest.
  3. F: CLOSE, reset, POSTTRAN, INTCALC at the chosen frequency, backup, COMBTRAN, statements, OPEN; reloads and reports on demand; completion checks replace WAITSTEP.
  4. B, D, E are not runnable repeatedly as written.
- **Recommendation:** Option F; it is the only order consistent with every data hand-off. It needs D-24, D-38, D-39 and D-40.
- **Affects:** All Airflow DAGs, Spring Batch job boundaries.
- **Items:** DEF-JCA7-005, Q-JCA7-001, DEF-JCOMB-002, DEF-JCTLM-001, DEF-JCTLM-006, Q-JCTLM-001, Q-JCTLM-004, DEF-JPOST-002, DEF-JRACCT-005, DEF-WAIT-001, NUM-WAIT-001, Q-WAIT-001, Q-WAIT-002

### D-38

**When statements are produced** · priority medium · 2 items

- **Question:** Where in the cycle and how often are statements produced?
- **As-is:** After COMBTRAN in the README and runbook; right after the files close, in parallel with posting, in CA-7 (DEF-JSTMT-006, Q-JSTMT-001).
- **Options:**
  1. After COMBTRAN, every cycle.
  2. After COMBTRAN, once per statement period.
  3. CA-7 position.
- **Recommendation:** After COMBTRAN, once per statement period (see D-48); statements then include the period's postings and interest.
- **Affects:** Statement DAG task.
- **Items:** DEF-JSTMT-006, Q-JSTMT-001

### D-39

**Where the authorization purge runs** · priority medium · 3 items

- **Question:** Does the authorization purge run before posting (CA-7) or after the cycle (README), and may it run beside online work?
- **As-is:** CA-7 starts posting only after the optional module's purge (DEF-JCA7-007); README and runbook put it after the cycle (Q-JCA7-003); IMS has no batch window for it (Q-JAUPG-002, INV-049).
- **Options:**
  1. Before posting (CA-7).
  2. After the cycle, as its own task (README).
  3. Any time beside online work.
- **Recommendation:** After the cycle as its own task that never gates posting; a base installation without the module must still post.
- **Affects:** Cycle DAG, purge task.
- **Items:** Q-JAUPG-002, DEF-JCA7-007, Q-JCA7-003

### D-40

**How much history to keep** · priority medium · 5 items

- **Question:** How long are transactions, rejects, backups and reports kept?
- **As-is:** The transaction master holds only the latest cycle; older transactions survive only in five backup generations (PINV-g-jobs-ops-04, Q-JTBKP-002); rejects and reports keep the last five runs, REPTFILE would keep ten (Q-JPOST-001, Q-JREJS-001, Q-JREPT-001, Q-JGDGB-001).
- **Options:**
  1. Keep 'last five runs' semantics.
  2. Keep all transactions in the database with a retention period per data class (for example 7 years for transactions, 90 days for rejects and reports).
- **Recommendation:** Retention by data class in days, set by your data-handling policy; generation counts depend on run frequency.
- **Affects:** Database design (no truncate per cycle), archive and purge jobs, backup policy.
- **Items:** Q-JGDGB-001, Q-JPOST-001, Q-JREJS-001, Q-JREPT-001, Q-JTBKP-002

### D-41

**Demo reloads of master data in production** · priority high · 6 items

- **Question:** May production run the delete-and-reload jobs that replace accounts, cards, customers, cross-references and category balances with the shipped sample data?
- **As-is:** ACCTFILE, CARDFILE, CUSTFILE, XREFFILE and TCATBALF delete and reload a file from sample data; TCATBALF wipes every running balance, and CA-7 schedule 032 loops it (DEF-JTCAT-002, Q-JACCT-001, Q-JTCAT-001).
- **Options:**
  1. Keep the reload jobs.
  2. Seed load for new environments only; restore from backup; no production reload.
- **Recommendation:** Seed-only; a production reload destroys customer data.
- **Affects:** Data-load jobs, environment set-up, DAG inventory.
- **Items:** Q-JACCT-001, Q-JCARD-002, Q-JCUST-001, DEF-JTCAT-002, Q-JTCAT-001, Q-JXREF-002

### D-42

**Which utility and demo jobs to keep** · priority low · 21 items

- **Question:** Which of the diagnostic, sample and unused jobs does the target keep?
- **As-is:** Four reader jobs print whole files (Q-JRACCT-001, Q-JCA7-004); READACCT writes test constants nothing reads (BR-ACRD-005, Q-ACRD-001); ESDSRRDS, INTRDRJ1/2 and FTPJCL are platform samples (Q-JESRR-001, Q-JIRD1-001, Q-JFTP-001); the timestamp index TRANIDX has no reader (Q-JTIDX-001); TRANVALD has no JCL or schedule and reports problems only as log lines with return code 0 (Q-TVAL-002, DEF-TVAL-004); the batch TRANREPT member has fixed dates (Q-JTRPT-001).
- **Options:**
  1. Keep them all.
  2. Drop the samples, READACCT, TRANIDX and TRANVALD; replace readers with an on-demand load check (counts, key ranges, totals); keep only the on-demand transaction report.
- **Recommendation:** Drop and replace as in the second option, unless operations name a user; they carry no business result.
- **Affects:** Job inventory, DAGs, post-load verification task.
- **Items:** BR-ACRD-005, BR-ACRD-006, DEF-ACRD-002, Q-ACRD-001, Q-ACRD-002, Q-JCA7-004, Q-JESRR-001, Q-JFTP-001, Q-JIRD1-001, Q-JRACCT-001, Q-JRCARD-001, Q-JRCUST-001, Q-JRXREF-001, DEF-JTIDX-004, Q-JTIDX-001, Q-JTRPT-001, BR-TVAL-005, DEF-TVAL-004, Q-TVAL-001, Q-TVAL-002, Q-TVAL-003

## S9. Reports and statements

### D-43

**What a statement covers** · priority high · 5 items

- **Question:** Is a statement per account or per card, which period does it cover, and what must it show?
- **As-is:** One statement per card, repeating the account balance; it lists every transaction in the master regardless of date and shows no date, period, limit or minimum payment; its total is not reconciled (DEF-STMT-005, Q-STMT-001, Q-STMT-002). It holds at most 51 cards and 10 transactions per card and overflows silently (DEF-JSTMT-005).
- **Options:**
  1. Keep (parity with the reference output).
  2. Fix: one statement per account per period, with date, period, limit, minimum payment and a reconciled total; no size limits.
- **Recommendation:** Fix; the as-is output is not a usable billing statement. Keep a parity mode only for the migration check.
- **Affects:** Statement batch step, template, period definition.
- **Items:** DEF-JSTMT-005, Q-JSTMT-002, DEF-STMT-005, Q-STMT-001, Q-STMT-002

### D-44

**Names and addresses cut on statements** · priority medium · 6 items

- **Question:** Should statements print full names, cities, ZIPs and descriptions?
- **As-is:** Multi-word names and cities print as the first word only (24 of 50 sample cities are cut); HTML lines end at a double space (DEF-STMT-002, DEF-STMT-003, BR-STMT-005, BR-STMT-006).
- **Options:**
  1. Keep.
  2. Fix: print full values.
- **Recommendation:** Fix; customers would see the truncation.
- **Affects:** Statement template.
- **Items:** BR-STMT-005, BR-STMT-006, BR-STMT-008, DEF-STMT-002, DEF-STMT-003, Q-STMT-003

### D-45

**Statement output format** · priority low · 5 items (1 security)

- **Question:** Which statement formats are produced, and how?
- **As-is:** Text plus one concatenated HTML file that nothing reads (Q-STMT-007); PDF comes from an external TXT2PDF tool not in the repository (DEF-JPDF-001); customer and merchant text goes into HTML unescaped (DEF-STMT-007).
- **Options:**
  1. Reproduce text and HTML as-is.
  2. One PDF per statement from a defined template, archived per cycle; HTML only if a consumer exists, always escaped.
- **Recommendation:** One escaped PDF per statement from a template; the original layout is not available anyway.
- **Affects:** Statement rendering, archive storage.
- **Items:** DEF-JPDF-001, Q-JPDF-001, Q-JPDF-002, DEF-STMT-007, Q-STMT-007

### D-46

**Transaction report date filter and early stop** · priority high · 5 items

- **Question:** Should the transaction report use one date range and skip records outside it instead of stopping?
- **As-is:** The batch report has two separately maintained date windows; one transaction outside the second window ends the report silently, drops every later card and all totals, and still returns 0 (DEF-JTRPT-001, DEF-TRPT-001, FLOW-TRPT-001).
- **Options:**
  1. Keep.
  2. Fix: one inclusive range from the request; skip out-of-range records; always print totals.
- **Recommendation:** Fix; the as-is report is silently incomplete.
- **Affects:** Report batch step.
- **Items:** BR-JTRPT-001, DEF-JTRPT-001, DEF-TRPT-001, FLOW-TRPT-001, Q-TRPT-001

### D-47

**Report totals per account or per card** · priority low · 3 items

- **Question:** Are report totals per account (as the label says) or per card (as computed)?
- **As-is:** The line labelled 'Account Total' is a per-card total, so an account with several cards gets several totals (DEF-TRPT-004, Q-TRPT-002); page control is for printing (Q-TRPT-003).
- **Options:**
  1. Keep per card, relabel 'Card Total'.
  2. Per account, sorted by account.
- **Recommendation:** Per account, as the label promises; treat the report as downloaded, not printed.
- **Affects:** Report query and template.
- **Items:** DEF-TRPT-004, Q-TRPT-002, Q-TRPT-003

### D-48

**Report request delivery and status** · priority medium · 4 items

- **Question:** How does a user get the report they requested online, and are they told when it finished or failed?
- **As-is:** The screen says the report was submitted for printing; the program never learns whether the job ran, failure is visible only in the job output, and the result goes to a dataset (DEF-RPTS-006, ERR-JTRPTP-001).
- **Options:**
  1. Keep 'submitted' only.
  2. Asynchronous run with status, notification and download.
  3. Online query instead of a job.
- **Recommendation:** Asynchronous run with status and download; it keeps the batch logic and gives feedback.
- **Affects:** Report request API, job tracking, React report page.
- **Items:** ERR-JTRPTP-001, Q-JTRPTP-001, DEF-RPTS-006, Q-RPTS-001

## S10. Validation strictness

### D-49

**Validate only changed fields on account update** · priority high · 2 items

- **Question:** Should account update validate only the fields the user changed, or every field (with migrated data cleaned first)?
- **As-is:** Every field is validated on save, and none of the 50 sample customers passes the edits, so no sample account can be updated (DEF-ACUP-112, Q-ACUP-101, INV-038).
- **Options:**
  1. Keep whole-record validation (no sample account is updatable).
  2. Validate changed fields only.
  3. Keep whole-record validation and clean the data during migration.
- **Recommendation:** Validate changed fields only; otherwise the screen does not work on real data.
- **Affects:** Account update validation, data-quality report for migration.
- **Items:** DEF-ACUP-112, Q-ACUP-101

### D-50

**Account update field rules** · priority medium · 21 items

- **Question:** How strict are account update's field edits (case, punctuation, ranges, phones, non-US addresses), and does a case-only correction count as a change?
- **As-is:** Only upper-case Y/N and state codes pass; names and cities allow only letters and spaces (O'Brien, St. Louis rejected); negative limits and cash above credit are accepted and extra digits silently cut; non-US customers can never pass; a case- or spacing-only correction is 'no change' (DEF-ACUP-109, DEF-ACUP-111, DEF-ACUP-113, DEF-ACUP-115, DEF-ACUP-110).
- **Options:**
  1. Keep all edits as-is.
  2. Fix: accept lower case, allow hyphen, apostrophe and full stop, add limit ranges, reject (never cut) overlong values, compare exactly after trimming; decide non-US address support separately.
- **Recommendation:** Fix as in the second option; the current edits reject valid customer data.
- **Affects:** Account update validation rules (shared front end and service).
- **Items:** BR-ACUP-102, DEF-ACUP-109, DEF-ACUP-110, DEF-ACUP-111, DEF-ACUP-113, DEF-ACUP-115, DEF-ACUP-118, DEF-ACUP-120, NUM-ACUP-101, VAL-ACUP-004, VAL-ACUP-005, VAL-ACUP-019, VAL-ACUP-021, VAL-ACUP-026, VAL-ACUP-033, Q-ACUP-102, Q-ACUP-104, Q-ACUP-105, Q-ACUP-106, Q-ACUP-107, Q-ACUP-108

### D-51

**Card update field rules** · priority medium · 7 items

- **Question:** Should card update keep upper-casing the embossed name, and how strict is expiry validation?
- **As-is:** The name is always upper-cased and a case-only change is ignored; expiry checks only month 1-12 and year 1950-2099, so past or invalid dates are saved (DEF-CRDU-008, DEF-CRDU-009, BR-CRDU-008).
- **Options:**
  1. Keep.
  2. Fix: store the name as typed, require a valid future expiry, accept lower-case y/n.
- **Recommendation:** Fix expiry validation; keep upper-case names only if embossing requires it.
- **Affects:** Card update validation.
- **Items:** BR-CRDU-008, DEF-CRDU-008, DEF-CRDU-009, NUM-CRDU-003, Q-CRDU-003, Q-CRDU-004, Q-CRDU-008

### D-52

**Transaction add and report request input checks** · priority medium · 12 items

- **Question:** Should transaction add check type and category codes and dates, and should IDs typed without leading zeros be accepted?
- **As-is:** Any type and category pair is accepted, and the user types the processing date (DEF-TRNA-002, DEF-TRNA-008); dates before 1582 and start-after-end report ranges are accepted (DEF-TRNA-007, DEF-RPTS-001, DEF-DTCK-004); IDs must be typed at full length with leading zeros (DEF-TRNA-006, DEF-TRNL-006).
- **Options:**
  1. Keep.
  2. Fix: check codes against reference tables, processing date = today, reject impossible dates and ranges, pad shorter numeric IDs.
- **Recommendation:** Fix; each as-is case only produces bad records or empty reports.
- **Affects:** Transaction add and report request validation, shared date validator.
- **Items:** DEF-DTCK-004, Q-DTCK-003, DEF-RPTS-001, DEF-RPTS-002, Q-RPTS-002, DEF-TRNA-002, DEF-TRNA-006, DEF-TRNA-007, DEF-TRNA-008, Q-TRNA-005, DEF-TRNL-006, DEF-TRNV-003

### D-53

**User administration input rules** · priority medium · 5 items (5 security)

- **Question:** Should user add and update restrict the type to defined roles, normalise and check the ID, and apply a password policy?
- **As-is:** Every field is stored as typed: any type character (lower-case `a` creates a regular user), spaces inside IDs, no password rule (BR-USRA-001, DEF-USRA-002, DEF-USRA-009, DEF-USRU-004).
- **Options:**
  1. Keep.
  2. Fix: defined roles only, upper-case IDs without spaces, password policy.
- **Recommendation:** Fix; a typo silently changes privileges.
- **Affects:** User admin validation.
- **Items:** BR-USRA-001, DEF-USRA-002, DEF-USRA-009, Q-USRA-001, DEF-USRU-004

## S11. Paging, lists and selection marks

### D-54

**List paging behaviour** · priority low · 20 items

- **Question:** Should lists page from server-side state with standard search, or reproduce the as-is paging quirks?
- **As-is:** PF7 on page 1 breaks the next PF8 (DEF-USRL-002); short pages leave blank rows or repeat users (DEF-USRL-011, DEF-USRL-014); a changed card filter searches only from the current page on (DEF-CRDL-006); Enter restarts the transaction list at page 1 (DEF-TRNL-005); backward paging repeats a page in the authorization summary (DEF-AUSM-002); user search is a case-sensitive start position (DEF-USRL-007); order is EBCDIC (Q-USRL-002).
- **Options:**
  1. Reproduce the quirks.
  2. Fix: server-side paging with stable keys, a changed filter restarts at page 1, case-insensitive prefix search, database collation order.
- **Recommendation:** Fix; the quirks show wrong or missing rows and serve no purpose.
- **Affects:** List APIs (keyset paging), React list components, collation.
- **Items:** BR-AUSM-006, DEF-AUSM-002, DEF-AUSM-011, Q-AUSM-001, BR-CRDL-004, DEF-CRDL-006, DEF-CRDL-010, NUM-CRDL-001, Q-CRDL-003, Q-CRDL-006, DEF-TRNL-005, DEF-TRNL-008, Q-TRNL-002, DEF-USRL-002, DEF-USRL-007, DEF-USRL-011, DEF-USRL-014, VAL-USRL-003, Q-USRL-001, Q-USRL-002

### D-55

**Selection marks survive paging** · priority medium · 6 items

- **Question:** Should selection marks be cleared on page change and tied to the record, not the row?
- **As-is:** Marks stay in the same screen rows after PF7/PF8, so Enter can open update or delete for a user or card the operator never marked; an invalid code's error is overwritten (DEF-USRL-012, DEF-CRDL-018, DEF-USRL-004).
- **Options:**
  1. Keep.
  2. Fix: clear marks on paging, bind the selection to the record key, show invalid-code errors.
- **Recommendation:** Fix; a delete can hit the wrong user.
- **Affects:** List screens.
- **Items:** DEF-CRDL-007, DEF-CRDL-018, Q-CRDL-007, DEF-TRNL-011, DEF-USRL-004, DEF-USRL-012

## S12. Audit

### D-56

**Audit trail** · priority medium · 2 items (2 security)

- **Question:** What must the target audit: sign-ons, admin changes, data changes, sensitive-data views?
- **As-is:** Nothing is audited: user records carry no created/changed by, deletes are physical (Q-USRA-002, Q-USRD-003); also open in other decisions: sign-on logging (DEF-SGN-002), who viewed which customer (Q-ACVW-002), card change history (Q-CRDU-005), who marked fraud (DEF-AUDT-006).
- **Options:**
  1. No audit (parity).
  2. Audit sign-on, user and role changes, account/card/customer changes, fraud marks and sensitive-data reveals; logical delete for users.
- **Recommendation:** Audit the listed events; they are needed to trace privilege and data changes.
- **Affects:** Audit table or event log, all write services, retention policy.
- **Items:** Q-USRA-002, Q-USRD-003

## S13. Error handling and failure signalling

### D-57

**How batch failures are signalled and handled** · priority high · 19 items

- **Question:** When a batch job fails or rejects records, what exit status does it give, does it stop the chain, and is it retried?
- **As-is:** The purge ends with return code 16 after committing deletes and may not stop posting (DEF-JAUPG-003, DEF-AUPG-006); import always ends with 0 (BR-IMPT-011); TRANVALD carries on with stale data after a read error (ERR-TVAL-003); a failed interest run keeps balance changes but drops the interest transactions (ERR-JINTC-001); a failed backup still empties the master (DEF-JTBKP-001); a failed statement run leaves old statements to be resent (DEF-JSTMT-001); batch type maintenance loops on a bad input file (DEF-TTBU-001); Control-M sets 5 reruns with no rule (Q-JCTLM-003).
- **Options:**
  1. Keep per-job behaviour.
  2. Fix: failure means non-zero exit and stops dependents; rejects give a warning status and a count; reset only after a verified backup; retries only for idempotent tasks, otherwise hold and alert.
- **Recommendation:** Fix with that policy; blind reruns and silent successes are the main operational risks.
- **Affects:** Every Spring Batch job's exit status, Airflow dependencies, retries and alerting.
- **Items:** DEF-AUPG-006, Q-AUPG-008, BR-IMPT-011, ERR-IMPT-004, Q-IMPT-002, DEF-JAUPG-003, ERR-JAUPG-001, Q-JAUPG-001, Q-JCTLM-003, ERR-JINTC-001, DEF-JSTMT-001, Q-JSTMT-003, DEF-JTBKP-001, Q-JTBKP-001, DEF-TTBU-001, Q-TTBU-001, Q-TTBU-003, Q-TTBU-004, ERR-TVAL-003

### D-58

**Technical codes in user messages** · priority low · 4 items

- **Question:** Should user messages drop raw CICS response codes and file names?
- **As-is:** Account view and other screens show messages like `Account:<id> not found in Acct Master file.Resp:000000013` (DEF-ACVW-016, VAL-ACVW-003).
- **Options:**
  1. Keep exact texts (parity tests compare them).
  2. Fix: plain messages; log the technical detail.
- **Recommendation:** Fix; keep the message meaning, drop the codes.
- **Affects:** Screen messages, logging, acceptance tests (message texts).
- **Items:** DEF-ACVW-016, NUM-ACVW-005, VAL-ACVW-003, Q-ACVW-008

## S14. Data model, keys, formats and migration

### D-59

**Transaction ID scheme** · priority high · 11 items

- **Question:** How are transaction IDs generated, and does the key stay the 16-character text ID?
- **As-is:** Online add and bill payment take 'highest ID + 1' with nothing reserving it; posting takes IDs from the daily file; interest builds IDs from an unchecked run parameter, so two runs can collide (INV-001, DEF-TRNA-003, DEF-INTC-010, DEF-JINTC-001). A binary-zero seed record exists so 'highest + 1' works on an empty file (DEF-JTRAN-005). Q-ENT-002 asks about a numeric surrogate key.
- **Options:**
  1. Keep 'highest + 1' and the text key.
  2. One database sequence shared by all writers, keeping the 16-digit format; no seed record.
  3. Also add a numeric surrogate key and keep the 16-character ID as a unique attribute.
- **Recommendation:** A shared sequence that keeps the 16-digit format, no seed record; add a surrogate key only if the data model team wants it.
- **Affects:** Transaction table key, ID generator, posting, interest, bill payment, transaction add.
- **Items:** Q-ENT-002, DEF-INTC-010, Q-INTC-006, DEF-JINTC-001, NUM-JINTC-001, Q-JINTC-001, DEF-JTRAN-005, Q-JTRAN-001, BR-TRNA-003, DEF-TRNA-003, Q-TRNA-003

### D-60

**One timestamp format** · priority medium · 4 items

- **Question:** Store transaction timestamps in one typed column, or keep the layouts the writers use?
- **As-is:** Online and batch writers store different text layouts; reports select on the first 10 characters (INV-010, Q-BILL-006); the export writes 4 undefined trailing bytes (DEF-EXPT-003).
- **Options:**
  1. Keep the text layouts.
  2. One typed `DATETIME2` column; convert during migration; format on output.
- **Recommendation:** One typed column; it removes the format mix without changing what users see.
- **Affects:** Transaction table schema, migration, report selection.
- **Items:** Q-BILL-006, DEF-EXPT-003, NUM-EXPT-001, Q-EXPT-001

### D-61

**Accounts with several cards, or none** · priority medium · 11 items

- **Question:** Can accounts with several cards (or no card) be viewed, updated and used, and is there a limit on cards per account?
- **As-is:** Account view and update work only for accounts with exactly one card; others fail with a file error or 'not found' (DEF-ACVW-003, DEF-ACVW-004, DEF-ACUP-012, INV-031); transaction add cannot resolve such an account and silently replaces a typed card (DEF-TRNA-004, DEF-TRNA-005); the authorization summary shows an error (DEF-AUSM-010); the indexes allow 2 or 8 cards (Q-JCARD-001).
- **Options:**
  1. Keep one card per account.
  2. Fix: look accounts up directly, allow any number of cards, let the user choose the card where needed.
- **Recommendation:** Fix; the single-card limit is a storage artefact.
- **Affects:** Account, card and cross-reference queries; transaction add; authorization summary; schema.
- **Items:** DEF-ACUP-012, DEF-ACVW-003, DEF-ACVW-004, Q-ACVW-003, Q-ACVW-005, DEF-AUSM-010, Q-AUSM-004, Q-JCARD-001, Q-JXREF-001, DEF-TRNA-004, DEF-TRNA-005

### D-62

**Export and import (branch migration)** · priority low · 12 items

- **Question:** Does the target keep the export/import round trip, and if so in which format and with which checks?
- **As-is:** Nothing schedules CBEXPORT/CBIMPORT and nothing reads the import files (Q-IMPT-004, Q-JIMPT-001); the record has fixed branch `0001` and region `NORTH` (Q-EXPT-004); there are no counts or trailer and the import validates nothing yet reports no errors (DEF-EXPT-007, DEF-IMPT-001).
- **Options:**
  1. Keep the 500-byte format.
  2. Replace with a database export/load, with counts and validation.
  3. Drop it.
- **Recommendation:** Replace with a database export/load unless an external receiver of the file is named.
- **Affects:** Migration tooling, export/import jobs.
- **Items:** DEF-EXPT-007, Q-EXPT-004, Q-EXPT-005, DEF-IMPT-001, DEF-IMPT-009, VAL-IMPT-001, Q-IMPT-001, Q-IMPT-004, DEF-JEXPT-001, Q-JEXPT-001, DEF-JIMPT-003, Q-JIMPT-001

## S15. Transaction types and reference data

### D-63

**One store for transaction types and categories** · priority medium · 11 items

- **Question:** Should transaction types and categories live in one table set read by online and batch, and which description text is authoritative?
- **As-is:** Types exist in Db2 (optional module) and in VSAM files; Db2 changes reach batch only after a manual extract and reload, which no schedule completes (DEF-JTREX-001, DEF-JCTLM-005, INV-019); the Db2 seed text is upper case with the typo `REVERAL`, the VSAM sample is mixed case (DEF-JCRDB-005).
- **Options:**
  1. Keep two stores.
  2. One SQL table set, seeded once with the VSAM text (typo fixed), maintained online.
- **Recommendation:** One store; two copies drift.
- **Affects:** Reference data tables, type maintenance, posting and report lookups, DAGs (no extract/reload).
- **Items:** DEF-JCRDB-005, Q-JCRDB-004, DEF-JCTLM-005, Q-JGDGD-001, Q-JMNTT-001, Q-JMNTT-002, Q-JTCATG-001, DEF-JTREX-001, Q-JTREX-001, Q-JTREX-002, Q-JTTYP-001

### D-64

**Deleting a type that has categories** · priority low · 3 items

- **Question:** May a transaction type with categories be deleted?
- **As-is:** Delete is refused by the foreign key (INV-017); every seeded type has categories and nothing maintains categories, so no seeded type can be deleted (DEF-TTLS-002).
- **Options:**
  1. Keep refusing, with a message naming the categories.
  2. Cascade delete.
  3. Add category maintenance.
- **Recommendation:** Keep refusing with a clear message; cascading would remove reference data used by posting.
- **Affects:** Type maintenance, schema constraint.
- **Items:** DEF-TTLS-002, Q-TTLS-002, Q-TTUP-005

### D-65

**Transaction type description rules** · priority low · 13 items

- **Question:** Which characters may type descriptions hold, does a case change count, how does the filter match, and are descriptions stored trimmed?
- **As-is:** Letters, digits and spaces only (Q-TTLS-004); a case-only change is rejected (DEF-TTUP-007); batch accepts codes the screens refuse (DEF-TTBU-003); the filter is case-sensitive and `%`/`_` act as wildcards (DEF-TTLS-016); updated descriptions are padded to 50 (DEF-TTLS-001).
- **Options:**
  1. Keep.
  2. Fix: same rules online and batch, case-sensitive change detection, case-insensitive literal filter, trimmed storage.
- **Recommendation:** Fix; keep the character set unless punctuation is needed.
- **Affects:** Type maintenance screens, batch maintenance, table data.
- **Items:** DEF-TTBU-003, Q-TTBU-002, BR-TTLS-003, BR-TTLS-008, DEF-TTLS-001, DEF-TTLS-004, DEF-TTLS-016, Q-TTLS-001, Q-TTLS-004, Q-TTLS-006, DEF-TTUP-007, Q-TTUP-004, Q-TTUP-007

### D-66

**Batch transaction type maintenance** · priority low · 1 items

- **Question:** Is batch type maintenance (MNTTRDB2) needed, and what is its input?
- **As-is:** The job names only a placeholder input file; no producer or layout is known (Q-TTBU-005).
- **Options:**
  1. Keep it, with a defined input interface.
  2. Drop it; the online screens suffice.
- **Recommendation:** Drop it unless a producer of the input is named.
- **Affects:** Job inventory, type maintenance.
- **Items:** Q-TTBU-005

### D-67

**Who maintains interest rates** · priority medium · 1 items

- **Question:** Who maintains disclosure groups (interest rates), and is a load without a `DEFAULT` group rejected?
- **As-is:** The only way to change rates is a full file reload; interest abends when a `DEFAULT` record is missing (Q-JDISC-001, INV-015).
- **Options:**
  1. Keep file reload.
  2. Admin maintenance function or controlled load that rejects data without `DEFAULT`.
- **Recommendation:** Controlled maintenance with the `DEFAULT` check; a missing default stops interest.
- **Affects:** Disclosure group table, admin function or load job.
- **Items:** Q-JDISC-001

## S16. Screen behaviour and navigation

### D-68

**Save and exit semantics** · priority low · 4 items

- **Question:** Should leaving a screen ever save silently, and how does a failed save behave?
- **As-is:** PF3 on user update saves without confirmation and the result is never seen (DEF-USRU-001, BR-USRU-005); card update's save result and retry flow are unclear (Q-CRDU-007).
- **Options:**
  1. Keep PF3 = save and exit.
  2. Fix: explicit save; leaving with unsaved changes warns; a failed save keeps the entered values.
- **Recommendation:** Fix; silent saves surprise users in a web UI.
- **Affects:** Edit screens (React), save APIs.
- **Items:** Q-CRDU-007, BR-USRU-005, DEF-USRU-001, Q-USRU-001

### D-69

**Invalid keys** · priority low · 5 items

- **Question:** Should unsupported keys show an invalid-key message instead of acting as Enter?
- **As-is:** In the card screens unknown keys act as Enter and Clear/PA keys lose the card (DEF-CRDV-005, DEF-CRDV-014, DEF-CRDU-012).
- **Options:**
  1. Keep.
  2. Fix: standard invalid-key message; no state loss.
- **Recommendation:** Fix; on the web this becomes ordinary button handling.
- **Affects:** Card screens.
- **Items:** Q-CRDL-009, DEF-CRDU-012, DEF-CRDV-005, DEF-CRDV-014, Q-CRDV-004

### D-70

**Options for modules that are not installed** · priority low · 2 items

- **Question:** Should the admin menu hide options of modules that are not installed?
- **As-is:** Options 5 and 6 show and fail with a green message when the transaction type module is absent (Q-ADMN-002, Q-ADMN-003).
- **Options:**
  1. Keep.
  2. Fix: show only installed functions.
- **Recommendation:** Fix.
- **Affects:** Admin menu.
- **Items:** Q-ADMN-002, Q-ADMN-003

### D-71

**Full transaction details on view** · priority low · 2 items

- **Question:** Should transaction view show the full description, merchant name and city, and the time of both timestamps?
- **As-is:** They are cut and times are not shown (DEF-TRNV-006).
- **Options:**
  1. Keep.
  2. Fix: show them in full.
- **Recommendation:** Fix; no business risk.
- **Affects:** Transaction view screen.
- **Items:** DEF-TRNV-006, Q-TRNV-002

## S17. MQ inquiry interfaces (optional VSAM-MQ module)

### D-72

**Inquiry message contract** · priority low · 9 items

- **Question:** Which message contract do the account and date inquiry services implement: the code's or the module README's, text or structured?
- **As-is:** The README describes a different request and reply than the code (DEF-MQAC-001, DEF-MQDT-001); account replies are labelled text with unedited overpunched amounts and no status (DEF-MQAC-014, Q-MQAC-008); any message counts as a date request (DEF-MQDT-004).
- **Options:**
  1. The code's contract, byte for byte.
  2. A structured reply (JSON, decimal amounts, status), unless an existing requester parses the text.
- **Recommendation:** Structured reply with a status, after confirming no requester parses the text; the module is optional.
- **Affects:** RabbitMQ message schemas, inquiry services.
- **Items:** DEF-MQAC-001, DEF-MQAC-014, Q-MQAC-007, Q-MQAC-008, DEF-MQDT-001, DEF-MQDT-004, DEF-MQDT-012, Q-MQDT-002, Q-MQDT-003

### D-73

**Reply routing and correlation** · priority medium · 5 items

- **Question:** Should replies go to the requester's reply-to queue with the standard correlation ID?
- **As-is:** Every reply goes to one fixed shared queue and copies the request's message and correlation IDs (DEF-MQAC-002, DEF-MQAC-003, DEF-MQDT-002).
- **Options:**
  1. Keep the shared queue.
  2. Fix: `reply_to` and `correlation_id` = request message ID.
- **Recommendation:** Fix; the shared queue exposes every requester's replies.
- **Affects:** RabbitMQ request/reply configuration.
- **Items:** DEF-MQAC-002, DEF-MQAC-003, Q-MQAC-002, DEF-MQDT-002, DEF-MQDT-003

### D-74

**Inquiry errors** · priority low · 3 items

- **Question:** On a file error, should the requester get an error reply and the request be kept for retry?
- **As-is:** The request is consumed, an error record with misleading fields is written and no reply is sent (DEF-MQAC-007, ERR-MQAC-005).
- **Options:**
  1. Keep.
  2. Fix: error reply plus dead-letter/retry.
- **Recommendation:** Fix.
- **Affects:** Inquiry consumer error path, dead-letter queue.
- **Items:** DEF-MQAC-007, ERR-MQAC-005, Q-MQAC-006

## S18. Obvious defects to fix in bulk

### D-75

**Fix obvious defects in bulk** · priority medium · 79 items

- **Question:** Approve fixing the defects listed in Appendix A, which no user would want to keep and whose fix does not change business results?
- **As-is:** Crashes and file errors on bad input, failures reported as success, stale data shown after a failed lookup, wrong message texts, lost leading digits on large amounts, binary zeros saved into text fields, statement table overflow, and similar (Appendix A lists every item with a one-line description).
- **Options:**
  1. Keep all as-is.
  2. Fix all (with exceptions you name).
  3. Decide one by one.
- **Recommendation:** Fix all; review Appendix A for any exception.
- **Affects:** Spread across screens and jobs; each fix gets a regression test.
- **Items:** the 79 items in Appendix A.

## Appendix A. Items in the bulk fix (D-75)

| Item | Spec | What the fix changes |
| :-- | :-- | :-- |
| BR-ACUP-004 | `programs/COACTUPC.md` | F12 re-read is kept; as-is a failed re-read still shows cleared values as a fetched account (DEF-ACUP-019). |
| DEF-ACUP-004 | `programs/COACTUPC.md` | As-is, when the customer record cannot be locked nothing is saved but the screen says changes were committed. |
| DEF-ACUP-007 | `programs/COACTUPC.md` | As-is, account update continues after a missing account or customer and can show and store values from records that were never read. |
| DEF-ACUP-010 | `programs/COACTUPC.md` | As-is, keys not valid in the current state silently act as Enter instead of showing the invalid-key message. |
| DEF-ACUP-019 | `programs/COACTUPC.md` | As-is, F12 after a failed re-read shows cleared values as if they were the fetched account, editable and used as the comparison base. |
| DEF-ACUP-101 | `programs/COACTUPC.md` | As-is, an empty account number shows "No input received" because the intended "Account number not provided" is overwritten. |
| DEF-ACUP-104 | `programs/COACTUPC.md` | As-is, a date the date service rejects is still marked valid afterwards, so the cursor is misplaced and the birth-date future check runs on an invalid date. |
| DEF-ACUP-106 | `programs/COACTUPC.md` | As-is, date combination checks run on parts that already failed, adding extra error flags and risking a crash. |
| DEF-ACUP-107 | `programs/COACTUPC.md` | As-is, one-digit date and phone parts are handled inconsistently and a passing date can be saved with spaces (for example 2024- 5- 7). |
| DEF-ACUP-122 | `programs/COACTUPC.md` | As-is, a blank stored field (group ID, middle name, address line 2, government ID) always counts as changed, and saving writes binary zeros into it. |
| ERR-ACUP-002 | `programs/COACTUPC.md` | As-is, after an account not-found or file error the fetch continues and may show the customer with unread account values. |
| ERR-ACUP-102 | `programs/COACTUPC.md` | As-is, a 29 February entry with a non-numeric year can crash the transaction instead of showing the year message. |
| NUM-ACUP-003 | `programs/COACTUPC.md` | Phone rebuild layout is kept; as-is an empty phone is saved with binary zeros inside the brackets. |
| VAL-ACUP-001 | `programs/COACTUPC.md` | The rejection of an empty account number is kept; only the visible message text is wrong as-is. |
| Q-ACUP-008 | `programs/COACTUPC.md` | Choose whether a customer lock failure is reported as a failure with retry; as-is it is reported as committed. |
| Q-ACUP-009 | `programs/COACTUPC.md` | Choose whether unsupported keys give an invalid-key message; as-is they act as Enter. |
| Q-ACUP-110 | `programs/COACTUPC.md` | Choose the message for an empty account number; as-is "No input received" overwrites the intended "Account number not provided". |
| DEF-ACVW-002 | `programs/COACTVWC.md` | As-is, account view continues after an account not found and can show an account panel from data that was never read, or crash. |
| DEF-ACVW-005 | `programs/COACTVWC.md` | As-is, account view drops the leading digit of amounts of a billion or more without warning. |
| DEF-ACVW-012 | `programs/COACTVWC.md` | As-is, every key other than PF3 acts as Enter on account view, without the invalid-key message other screens show. |
| DEF-ACVW-018 | `programs/COACTVWC.md` | As-is, after a failed lookup the session keeps the previous account's customer and card numbers paired with the new or zero account number. |
| ERR-ACVW-001 | `programs/COACTVWC.md` | Which reads are errors is kept; as-is the lookup continues after an account read failure (DEF-ACVW-002). |
| VAL-ACVW-004 | `programs/COACTVWC.md` | The account-not-found rejection is kept; as-is the customer is still read afterwards. |
| VAL-ACVW-006 | `programs/COACTVWC.md` | Enter and PF3 are kept; as-is all other keys act as Enter without an invalid-key message. |
| Q-ACVW-004 | `programs/COACTVWC.md` | Choose whether a missing account or customer stops the view with only the message (the spec expects yes) and whether a failed lookup clears the kept customer and card numbers. |
| Q-ACVW-006 | `programs/COACTVWC.md` | Choose whether to widen the amount display for values of a billion or more; as-is the leading digit is lost. |
| Q-ACVW-009 | `programs/COACTVWC.md` | Choose whether other keys give an invalid-key message on account view and whether PF3 keeps the caller's user type; as-is keys act as Enter and PF3 forces regular user. |
| BR-ADMN-003 | `programs/COADM01C.md` | The "not installed" message is kept but the next key after it is lost (DEF-ADMN-006); the fix is to process the next key normally. |
| DEF-ADMN-006 | `programs/COADM01C.md` | After an "option not installed" message the next key is ignored and the user must retype the option; the fix is to process the next key normally. |
| DEF-AUDT-001 | `programs/COPAUS1C.md` | Fix PF5 and PF8 acting without checking the read; as-is PF5 toggles fraud on whatever record area is left when the read failed. |
| DEF-AUDT-002 | `programs/COPAUS1C.md` | Fix the Auth Code field showing the processing code instead of the authorization ID code. |
| DEF-AUDT-003 | `programs/COPAUS1C.md` | Fix the screen showing the new fraud status after a failed change; as-is it shows the toggled value until the next Enter. |
| DEF-AUDT-005 | `programs/COPAUS1C.md` | Fix the silent failure when the fraud update call fails; as-is no message is shown. |
| Q-AUDT-001 | `programs/COPAUS1C.md` | Confirm that Auth Code shows the authorization ID code instead of the processing code. |
| Q-AUDT-002 | `programs/COPAUS1C.md` | Confirm that a failed fraud change keeps the stored status and always shows a message. |
| DEF-CRDL-008 | `programs/COCRDLIC.md` | Read-ahead ignores the filter, so PF8 can show an empty page and later paging goes wrong; fix by applying the filter to the read-ahead. |
| DEF-CRDL-009 | `programs/COCRDLIC.md` | Paging back to the start of the file shows a file error or empty rows; fix by treating start of file as the first page. |
| DEF-CRDL-019 | `programs/COCRDLIC.md` | Paging back to page 1 shows the refusal message "no previous pages" although it worked; fix the message. |
| DEF-CRDU-004 | `programs/COCRDUPC.md` | Card update reads undefined bytes beyond the caller's session data, which can send the user back to a previous screen at random; fix by initialising state on first entry. |
| FLOW-CRDU-001 | `programs/COCRDUPC.md` | Card update first entry copies undefined bytes past the passed session data and tests a stale menu condition; fix by initialising state explicitly. |
| DEF-CRDV-007 | `programs/COCRDSLC.md` | The "not provided" test runs before the numeric test and may abend on bad input; fix by validating the format first. |
| DEF-CRDV-008 | `programs/COCRDSLC.md` | Rejected non-numeric input is erased from the screen; fix by redisplaying what the user typed. |
| DEF-CRDV-012 | `programs/COCRDSLC.md` | Messages call required fields optional "filters" and the key legend says "Search Cards"; fix the texts. |
| VAL-CRDV-008 | `programs/COCRDSLC.md` | Invalid fields are shown empty instead of with the typed value; fix by showing the typed value in red, keeping the red marking. |
| DEF-IMPT-002 | `programs/CBIMPORT.md` | The import counts an error record as written even when the write failed, so its error count always equals the unknown-type count. |
| DEF-JCRDB-002 | `jobs/CREADB21.md` | On a new installation the setup job loads the transaction types but skips the categories because of its step conditions. |
| Q-JCRDB-001 | `jobs/CREADB21.md` | Whether categories should be loaded on a new installation and the setup made rerunnable; as-is they are skipped and a rerun fails on duplicates. |
| ERR-JIMPT-002 | `jobs/CBIMPORT-JOB.md (nl/G)` | Fix the import so a failed error-file write fails or flags the run and the end message reflects the real error count; as-is it continues and always says no validation errors. |
| DEF-JPCAT-001 | `jobs/PRTCATBL.md (nl/G)` | The category balance listing prints negative balances without a sign, so -12.34 looks like +12.34. |
| Q-JPCAT-001 | `jobs/PRTCATBL.md (nl/G)` | The user must confirm the listing shows the sign of negative balances; as-is it does not. |
| SE-JTRPT-002 | `jobs/TRANREPT.md (nl/G)` | The report is sorted by card number, but the order of transactions within one card is not guaranteed. |
| DEF-RPTS-003 | `programs/CORPT00C.md` | Non-digit date parts are turned into zeros, so the not-a-valid-year messages can never be shown. |
| BR-STIO-004 | `programs/CBSTM03B.md` | Account keys passed to the statement file-access routine are right-aligned to 11 digits; non-digit keys are interpreted unpredictably. |
| DEF-STIO-002 | `programs/CBSTM03B.md` | The statement file-access routine silently cuts account keys longer than 11 digits and interprets non-digit characters, so a malformed key can read the wrong account. |
| DEF-STIO-003 | `programs/CBSTM03B.md` | The statement file-access routine silently ignores unsupported write/rewrite requests and unknown file names and returns success. |
| Q-STIO-001 | `programs/CBSTM03B.md` | Decide whether the target file-access interface implements write/rewrite, rejects them or drops them; as-is they are silently ignored. |
| Q-STIO-003 | `programs/CBSTM03B.md` | Decide whether non-digit account keys should be rejected; as-is they produce platform-dependent key bytes. |
| DEF-STMT-001 | `programs/CBSTM03A.md` | The statement job holds at most 51 cards of 10 transactions each with no check, so larger volumes produce wrong statements, lost transactions or a crash. |
| DEF-STMT-004 | `programs/CBSTM03A.md` | Statement balances of 1,000,000,000.00 or more lose their highest digit because the print field is one digit short. |
| DEF-STMT-010 | `programs/CBSTM03A.md` | A day with no transactions at all makes the statement job abend instead of producing statements without transactions. |
| Q-STMT-005 | `programs/CBSTM03A.md` | The statement job has a fixed limit of 51 cards and 10 transactions per card; the question is real volumes and whether any limit is intended (the spec says the target must not keep it). |
| DEF-TRNA-014 | `programs/COTRN02C.md` | Answering N to the add confirmation does not cancel; it repeats the prompt. |
| DEF-TRNL-002 | `programs/COTRN00C.md` | The transaction list drops the highest digit of amounts of 100 million or more. |
| DEF-TRNL-003 | `programs/COTRN00C.md` | Returning to the list with PF5 from the view skips the first transaction of the file. |
| DEF-TRNL-012 | `programs/COTRN00C.md` | PF7 on the first page re-enables PF8, which can then show blank rows, jump to a wrong page or abend. |
| Q-TRNL-004 | `programs/COTRN00C.md` | Decide whether to widen the amount column to show 9 integer digits. |
| DEF-TRNV-002 | `programs/COTRN01C.md` | The transaction view drops the highest digit of amounts of 100 million or more. |
| DEF-TTLS-005 | `programs/COTRTLIC.md` | The transaction type list page counter wraps after page 9, so page 10 shows as page 1 and the user cannot page back. |
| DEF-TTLS-006 | `programs/COTRTLIC.md` | Paging back when fewer than a page of earlier rows exists shows a database error and a partly empty page. |
| DEF-TTLS-007 | `programs/COTRTLIC.md` | When the filter count query fails the list says no records were found instead of showing the error. |
| DEF-TTLS-008 | `programs/COTRTLIC.md` | After a successful delete the list still shows the deleted row and the session loses the signed-on user ID and type. |
| DEF-TTUP-001 | `programs/COTRTUPC.md` | After a failed insert or update the update screen says the changes were committed although nothing was written. |
| DEF-TTUP-002 | `programs/COTRTUPC.md` | After the database refuses a delete the update screen gets stuck and the next Enter abends the task. |
| Q-TTUP-003 | `programs/COTRTUPC.md` | Whether to report failed saves as failures and stay on the shown type after a refused delete; the spec expects yes. |
| VAL-USRA-003 | `programs/COUSR01C.md` | PF12 is advertised as Exit on the user add screen but is rejected as an invalid key; the fix is to make PF12 exit or drop the label. |
| DEF-USRD-002 | `programs/COUSR03C.md` | When the user to delete is not found, the screen shows "Unable to Update User" instead of "User ID NOT found"; the fix is to report not found. |
| DEF-USRL-001 | `programs/COUSR00C.md` | Searching for a user ID beyond the last user (or an empty file) abends the list task instead of showing a message; the fix is to show the message and stay on the screen. |
| ERR-USRL-001 | `programs/COUSR00C.md` | A user browse that finds no record continues and abends the task; the fix is to stop and show the "top of page" message. |
| DEF-USRU-002 | `programs/COUSR02C.md` | When the user to update is not found, the save shows "Unable to Update User" instead of "User ID NOT found"; the fix is to report not found. |

## Appendix B. Items that need no user decision

Class (c): a mainframe-only or platform question that the modernized system does not depend on. Class (d): already answered in the spec.

| Item | Spec | Class | Why no decision is needed |
| :-- | :-- | :-- | :-- |
| DEF-JACCT-001 | `jobs/ACCTFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JACCT-001 | `jobs/ACCTFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| DEF-JCSD-009 | `jobs/CARDDEMO-CSD.md (nl/G)` | c | CICS trace with CONFDATA(NO) is a region setting; the target has no CICS trace, and its log masking is covered by the pii-in-batch-outputs decision. |
| Q-JCSD-003 | `jobs/CARDDEMO-CSD.md (nl/G)` | c | Whether DFHCSDUP accepts the CSD export with metadata is a CICS tooling question; the target has no CSD. |
| Q-JCSD-004 | `jobs/CARDDEMO-CSD.md (nl/G)` | c | COCRDSEC and CDV1 have no source in the repository, so the target has nothing to rebuild; only the CICS definition refers to them. |
| DEF-JCARD-001 | `jobs/CARDFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| DEF-JCARD-002 | `jobs/CARDFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| DEF-JCARD-004 | `jobs/CARDFILE.md (nl/G)` | c | VSAM alternate-index record size limits a card index record to 8 cards; a SQL index has no such limit, and the business limit is decided under Q-JCARD-001. |
| ERR-JCARD-001 | `jobs/CARDFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JCARD-002 | `jobs/CARDFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| ERR-JCARD-003 | `jobs/CARDFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| Q-JCSDUP-001 | `jobs/CBADMCDJ.md (nl/G)` | c | Whether the CBADMCDJ CSD-definition job is still used is a mainframe operations question; the target has no CICS resource definitions. |
| Q-JCSDUP-002 | `jobs/CBADMCDJ.md (nl/G)` | c | DFHCSDUP handling of duplicate DEFINE commands is CICS utility behaviour; the target has no CSD. |
| DEF-JEXPT-003 | `jobs/CBEXPORT-JOB.md (nl/G)` | c | Unconditional IDCAMS return-code reset before the export define; the target batch framework writes its export output without VSAM delete/define. |
| ERR-JEXPT-001 | `jobs/CBEXPORT-JOB.md (nl/G)` | c | Missing JCL COND and CEE3ABD abend after an I/O error in the export job; the target batch framework fails the step and marks the run failed, and partial-output handling is a framework setting. |
| DEF-JIMPT-001 | `jobs/CBIMPORT-JOB.md (nl/G)` | c | Import JCL allocates its outputs as NEW without a pre-delete, so a rerun fails at allocation; the target has no dataset catalog. |
| ERR-JCLOSE-001 | `jobs/CLOSEFIL.md (nl/G)` | c | The CICS close command result not reaching the job return code is a CICS/SDSF behaviour; the target replaces the CICS file close with its own batch-window control. |
| Q-JCLOSE-002 | `jobs/CLOSEFIL.md (nl/G)` | c | Waiting for a CICS close confirmation is a mainframe question; the target has no CICS files to close. |
| DEF-JCOMB-001 | `jobs/COMBTRAN.md (nl/G)` | c | Selecting inputs by newest GDG generation is a z/OS GDG behaviour; the target replaces GDGs and selects a cycle's data by cycle identifier. |
| ERR-JCOMB-001 | `jobs/COMBTRAN.md (nl/G)` | c | A SORT step without COND letting the load step run is a JCL defect in a utility step that the target replaces. |
| Q-JCOMB-001 | `jobs/COMBTRAN.md (nl/G)` | c | The target replaces GDG relative generations; selecting the posted and interest sets by cycle identifier is the natural design of a database-backed merge and needs no user choice. |
| Q-JCSD2-001 | `jobs/CRDDEMO2-CSD.md` | c | Asks where the CICS MQ, IMS and Db2 connections are defined, which the target replaces with Spring Boot configuration for RabbitMQ and SQL Server. |
| Q-JCSD2-002 | `jobs/CRDDEMO2-CSD.md` | c | Db2 THREADLIMIT is a CICS-Db2 attachment setting; the target uses a SQL Server connection pool. |
| Q-JCSDD-001 | `jobs/CRDDEMOD-CSD.md` | c | Where the CICS Db2 connection is defined is CICS region setup; the target connects to SQL Server through Spring configuration. |
| Q-JCSDD-002 | `jobs/CRDDEMOD-CSD.md` | c | THREADLIMIT is a CICS Db2 thread setting; the target sizes its own connection pool. |
| Q-JCSDM-001 | `jobs/CRDDEMOM-CSD.md` | c | IBM MQ trigger objects are platform definitions; the target uses RabbitMQ consumers, not triggered transactions. |
| Q-JCRDB-002 | `jobs/CREADB21.md` | c | Whether DSNTEP4 accepts the seed SQL syntax is a Db2 utility question; the target seeds data through its own SQL Server migration. |
| ERR-JSTMT-002 | `jobs/CREASTMT.md (nl/G)` | c | The undefined abend code from calling CEE3ABD without arguments is an LE behaviour; the target reports failures through its own job status. |
| DEF-JCUST-001 | `jobs/CUSTFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| DEF-JCUST-002 | `jobs/CUSTFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| ERR-JCUST-001 | `jobs/CUSTFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JCUST-002 | `jobs/CUSTFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| DEF-JDBPA-001 | `jobs/DBPAUTP0.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this unload data set deletion order does not carry over. |
| Q-JDBPA-001 | `jobs/DBPAUTP0.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this question about the HD unload purpose and reload job is mainframe-only. |
| Q-JDBPA-002 | `jobs/DBPAUTP0.md` | c | IMS /DBR and batch read authorization do not exist in the target; SQL Server handles concurrent reads. |
| DEF-JDCUST-001 | `jobs/DEFCUST.md (nl/G)` | c | Obsolete DEFCUST job deletes and defines different VSAM cluster names; the target needs no equivalent of this job. |
| DEF-JDCUST-002 | `jobs/DEFCUST.md (nl/G)` | c | Obsolete DEFCUST cluster name and key do not match the customer master; the target defines its own customer table. |
| ERR-JDCUST-001 | `jobs/DEFCUST.md (nl/G)` | c | IDCAMS delete of a non-existent cluster returns 8 in an obsolete job; the target needs no equivalent. |
| ERR-JDCUST-002 | `jobs/DEFCUST.md (nl/G)` | c | Obsolete define job fails on rerun because the cluster exists; the target needs no equivalent. |
| Q-JDCUST-001 | `jobs/DEFCUST.md (nl/G)` | c | Asks whether the obsolete DEFCUST job can be removed from the repository; the spec states the target needs no equivalent, so it is repository housekeeping, not a target decision. |
| DEF-JGDGB-001 | `jobs/DEFGDGB.md (nl/G)` | c | IDCAMS LASTCC/MAXCC reset in the GDG base definition job; the target has no GDGs. |
| DEF-JGDGB-003 | `jobs/DEFGDGB.md (nl/G)` | c | Two JCL jobs define the same GDG base with different attributes; the target has no GDGs, and report retention is decided under Q-JREPT-001. |
| DEF-JDISC-001 | `jobs/DISCGRP.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JDISC-001 | `jobs/DISCGRP.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| DEF-JUSEC-002 | `jobs/DUSRSECJ.md (nl/G)` | c | The SYSAD unit name is a JCL typo in an allocation step; the target replaces the JCL reload with its own provisioning. |
| DEF-JUSEC-003 | `jobs/DUSRSECJ.md (nl/G)` | c | The unconditional IDCAMS return code reset is a JCL utility defect; the target replaces the delete-define-load job. |
| ERR-JUSEC-001 | `jobs/DUSRSECJ.md (nl/G)` | c | Step sequencing without COND parameters is JES behaviour of the reload job; the target replaces the job with its own provisioning. |
| DEF-JESRR-002 | `jobs/ESDSRRDS.md (nl/G)` | c | UNIT=SYSAD typo in JCL dataset allocation; the target has no JCL unit allocation. |
| DEF-JESRR-004 | `jobs/ESDSRRDS.md (nl/G)` | c | Unconditional IDCAMS return-code reset in an ESDS/RRDS sample job; the target has no VSAM ESDS or RRDS. |
| DEF-JFTP-002 | `jobs/FTPJCL.md (nl/G)` | c | Misspelled high-level qualifier on a z/OS dataset name in a sample FTP job; the target has no dataset catalog. |
| ERR-JFTP-001 | `jobs/FTPJCL.md (nl/G)` | c | z/OS batch FTP client does not set a failing return code without the EXIT option; platform behaviour the target does not use. |
| Q-JFTP-002 | `jobs/FTPJCL.md (nl/G)` | c | Asks the mainframe team about the z/OS FTP client log location and FTP.DATA settings; a pure platform question. |
| DEF-JINTC-004 | `jobs/INTCALC.md (nl/G)` | c | The mismatch between a non-unique VSAM alternate index and a program that expects unique keys is VSAM AIX DUPKEY semantics; the target reads cards by account with SQL, and the program-side item covers the business rule. |
| DEF-JIRD1-002 | `jobs/INTRDRJ1.md (nl/G)` | c | Missing JCL COND before an internal-reader submit step in a sample job; the target has no JES internal reader. |
| ERR-JIRD1-001 | `jobs/INTRDRJ1.md (nl/G)` | c | JCL step and allocation failure behaviour of an internal-reader sample job; the target has no JES or IEBGENER. |
| DEF-JLDPA-002 | `jobs/LOADPADB.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this merge-versus-restore behaviour of the IMS load job does not carry over. |
| Q-JLDPA-001 | `jobs/LOADPADB.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this question of who empties the IMS database before reload is mainframe-only. |
| ERR-JOPEN-001 | `jobs/OPENFIL.md (nl/G)` | c | CICS open command failures not reaching the job return code are a CICS/SDSF behaviour; the target has no CICS files to reopen. |
| Q-JOPEN-001 | `jobs/OPENFIL.md (nl/G)` | c | Confirming that CICS files are open again is a mainframe question; the target replaces the file hand-over (see Q-JCLOSE-001 for the batch/online choice). |
| DEF-JRACCT-001 | `jobs/READACCT.md (nl/G)` | c | UNIT=SYSAD typo in JCL dataset allocation; the target has no JCL unit allocation. |
| DEF-JRCUST-002 | `jobs/READCUST.md (nl/G)` | c | NOTIFY=&SYUID typo on the JCL job card; a JCL typo the target does not carry over. |
| DEF-JREPT-001 | `jobs/REPTFILE.md (nl/G)` | c | Conflicting GDG base definitions between REPTFILE and DEFGDGB; the target has no GDGs, and report retention is decided under Q-JREPT-001. |
| NUM-JREPT-001 | `jobs/REPTFILE.md (nl/G)` | c | GDG LIMIT and NOSCRATCH space behaviour for report generations; the target has no GDGs, and report retention is decided under Q-JREPT-001. |
| SE-JREPT-001 | `jobs/REPTFILE.md (nl/G)` | c | IDCAMS definition of the report GDG base; the target has no GDGs. |
| DEF-JCA7-001 | `jobs/SCHED-CA7.md (nl/G)` | c | What starts the CA-7 chains (schedule member 000652) is CA-7 configuration; the target defines its own Airflow schedules. |
| DEF-JCA7-002 | `jobs/SCHED-CA7.md (nl/G)` | c | Endless CA-7 trigger loops under schedule ID 030 are CA-7 trigger semantics; the Airflow DAGs are written fresh. |
| DEF-JCA7-006 | `jobs/SCHED-CA7.md (nl/G)` | c | Parallel CA-7 paths started from one CLOSEFIL are CA-7 trigger semantics; the Airflow DAG orders its tasks explicitly. |
| DEF-JCA7-008 | `jobs/SCHED-CA7.md (nl/G)` | c | Endless loops under schedule IDs 031 and 032 are CA-7 trigger semantics; the Airflow DAGs are written fresh. |
| DEF-JCA7-009 | `jobs/SCHED-CA7.md (nl/G)` | c | The CA-7 default schedule ID on DEMAND is a CA-7 behaviour; the target starts DAGs through Airflow. |
| FLOW-JCA7-001 | `jobs/SCHED-CA7.md (nl/G)` | c | Describes CA-7 trigger chain 1 as listed; the business content is decided under batch-cycle-order and the Airflow DAG is written fresh. |
| FLOW-JCA7-002 | `jobs/SCHED-CA7.md (nl/G)` | c | Describes CA-7 trigger chain 2 as listed; the business content is decided under batch-cycle-order and reference-data-reload. |
| FLOW-JCA7-003 | `jobs/SCHED-CA7.md (nl/G)` | c | Describes CA-7 trigger chain 3 as listed; statement placement is decided under statements-schedule. |
| FLOW-JCA7-004 | `jobs/SCHED-CA7.md (nl/G)` | c | Describes the CA-7 schedule ID 031 loop; CA-7 trigger semantics the target does not reproduce. |
| FLOW-JCA7-006 | `jobs/SCHED-CA7.md (nl/G)` | c | Describes CA-7 schedule ID inheritance across the listing; CA-7 trigger semantics the target does not reproduce. |
| Q-JCA7-002 | `jobs/SCHED-CA7.md (nl/G)` | c | Whether the CA-7 listing is real and what schedule member 000652 contains is a pure mainframe confirmation; the target defines its own schedule. |
| DEF-JCTLM-004 | `jobs/SCHED-CONTROLM.md (nl/G)` | c | Two smart folders deleting the same condition is Control-M condition semantics; the Airflow DAGs are written fresh. |
| DEF-JCTLM-008 | `jobs/SCHED-CONTROLM.md (nl/G)` | c | Daily and monthly Control-M folders running concurrently without a linking condition is Control-M configuration; Airflow serialises DAG runs explicitly. |
| DEF-JCTLM-009 | `jobs/SCHED-CONTROLM.md (nl/G)` | c | Order-date scoped Control-M conditions not serialising days is Control-M semantics; the Airflow DAG can limit itself to one active run. |
| FLOW-JCTLM-001 | `jobs/SCHED-CONTROLM.md (nl/G)` | c | Describes the Control-M daily folder as defined; its business content is decided under batch-cycle-order. |
| FLOW-JCTLM-003 | `jobs/SCHED-CONTROLM.md (nl/G)` | c | Describes the Control-M Saturday disclosure group folder; its business content is decided under reference-data-reload. |
| FLOW-JCTLM-004 | `jobs/SCHED-CONTROLM.md (nl/G)` | c | Describes the Control-M Saturday Db2 extract folder; its business content is decided under reference-data-reload. |
| FLOW-JCTLM-005 | `jobs/SCHED-CONTROLM.md (nl/G)` | c | Describes the forced-only Control-M monthly folder; its business content is decided under interest-frequency. |
| Q-JCTLM-005 | `jobs/SCHED-CONTROLM.md (nl/G)` | c | Which Control-M platform runs the table is a pure platform confirmation; the target uses Airflow. |
| DEF-JTCAT-001 | `jobs/TCATBALF.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JTCAT-001 | `jobs/TCATBALF.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| DEF-JTBKP-002 | `jobs/TRANBKP.md (nl/G)` | c | An IDCAMS MAXCC reset hiding a failed delete is a defect in a utility step the target replaces. |
| DEF-JTBKP-005 | `jobs/TRANBKP.md (nl/G)` | c | A never-loaded VSAM cluster failing to open for REPRO is VSAM/IDCAMS behaviour the target does not have. |
| ERR-JTBKP-001 | `jobs/TRANBKP.md (nl/G)` | c | An IDCAMS delete step without COND is a JCL defect in a utility step the target replaces. |
| DEF-JTCATG-001 | `jobs/TRANCATG.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JTCATG-001 | `jobs/TRANCATG.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| DEF-JTRAN-001 | `jobs/TRANFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| DEF-JTRAN-002 | `jobs/TRANFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| DEF-JTRAN-006 | `jobs/TRANFILE.md (nl/G)` | c | VSAM alternate-index record size limits one processed timestamp to 19 transactions; a SQL index has no such limit. |
| DEF-JTRAN-007 | `jobs/TRANFILE.md (nl/G)` | c | An unused VSAM alternate index on the transaction file; the target defines its own SQL indexes as its queries need. |
| ERR-JTRAN-001 | `jobs/TRANFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JTRAN-002 | `jobs/TRANFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| ERR-JTRAN-003 | `jobs/TRANFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| SE-JTRAN-004 | `jobs/TRANFILE.md (nl/G)` | c | Describes the VSAM REPRO load of the seed file into the new cluster; the target does not load VSAM, and whether a seed record is needed at all is decided under Q-JTRAN-001. |
| Q-JTRAN-002 | `jobs/TRANFILE.md (nl/G)` | c | Asks whether the unused VSAM timestamp index is used externally; nothing in the repository reads it (DEF-JTRAN-007) and SQL indexes have no 19-per-timestamp limit, so the target simply indexes what its queries need. |
| DEF-JTIDX-001 | `jobs/TRANIDX.md (nl/G)` | c | An IDCAMS index job that does not delete the old index first is a defect in a utility step the target replaces. |
| DEF-JTIDX-002 | `jobs/TRANIDX.md (nl/G)` | c | The limit on transaction IDs per alternate key record is VSAM AIX behaviour; a SQL index has no such limit. |
| DEF-JTIDX-003 | `jobs/TRANIDX.md (nl/G)` | c | An alternate index missing from the CICS upgrade set is CICS/VSAM behaviour; the target maintains SQL indexes automatically. |
| ERR-JTIDX-001 | `jobs/TRANIDX.md (nl/G)` | c | IDCAMS index steps without COND failing on an existing index is a defect in utility steps the target replaces. |
| DEF-JTRPTP-003 | `jobs/TRANREPT-PROC.md (nl/G)` | c | Online report requests consuming backup generations is a GDG behaviour; the target report reads the store directly and does not write backups. |
| DEF-JTRPTP-006 | `jobs/TRANREPT-PROC.md (nl/G)` | c | Interleaved job lines in a CICS transient data queue and JES duplicate job names are platform behaviour; the target submits a report as one API call. |
| DEF-JTRPT-002 | `jobs/TRANREPT.md (nl/G)` | c | A report job writing into the backup generation that COMBTRAN reads is GDG relative-generation behaviour; the target report does not write backups. |
| ERR-JTRPT-001 | `jobs/TRANREPT.md (nl/G)` | c | Steps without COND running after a failed unload is a JCL defect in utility steps the target replaces. |
| Q-JTRPT-002 | `jobs/TRANREPT.md (nl/G)` | c | Copying the whole transaction master before reporting is an IDCAMS/SORT artefact; the target report queries the store directly. |
| DEF-JTTYP-001 | `jobs/TRANTYPE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JTTYP-001 | `jobs/TRANTYPE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| ERR-JPDF-001 | `jobs/TXT2PDF1.md (nl/G)` | c | A COND test that can never fire and the CA-7 trigger guard are JCL and CA-7 behaviour; the target orders the PDF task after a successful statement task. |
| Q-JPDF-003 | `jobs/TXT2PDF1.md (nl/G)` | c | Which condition-code test the CA-7 definitions use is a pure CA-7 confirmation; the target stops downstream tasks on failure. |
| DEF-JULGS-001 | `jobs/UNLDGSAM.md` | c | GSAM data set allocation is z/OS-specific and no job reads these files; the target does not use GSAM. |
| DEF-JULGS-002 | `jobs/UNLDGSAM.md` | c | DBRC and batch-region DISP=SHR access are IMS mechanics; the target reads SQL Server with normal transaction isolation. |
| DEF-JULGS-003 | `jobs/UNLDGSAM.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this kept partial GSAM files do not carry over. |
| Q-JULGS-001 | `jobs/UNLDGSAM.md` | c | DBRC settings and stopping the IMS database are IMS operations the target does not have. |
| Q-JULGS-002 | `jobs/UNLDGSAM.md` | c | GSAM allocation, space and DCB are z/OS details the target does not use. |
| DEF-JULPA-001 | `jobs/UNLDPADB.md` | c | A JCL disposition defect in a delete step that the target does not have. |
| DEF-JULPA-002 | `jobs/UNLDPADB.md` | c | DBRC-off batch reads are IMS mechanics; the target reads SQL Server with normal transaction isolation. |
| Q-JULPA-001 | `jobs/UNLDPADB.md` | c | Unload file space sizing is a z/OS data set question; the target does not produce IMS unload files. |
| DEF-JWAIT-002 | `jobs/WAITSTEP.md (nl/G)` | c | How the pause length is converted on z/OS is compiler and platform behaviour; the target replaces WAITSTEP with completion checks. |
| DEF-JWAIT-003 | `jobs/WAITSTEP.md (nl/G)` | c | A fixed pause between jobs replaces no business rule; the target replaces WAITSTEP with explicit task dependencies. |
| Q-JWAIT-001 | `jobs/WAITSTEP.md (nl/G)` | c | What the pause protects against is a mainframe question; the target replaces it with explicit task dependencies and checks. |
| DEF-JXREF-001 | `jobs/XREFFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| DEF-JXREF-002 | `jobs/XREFFILE.md (nl/G)` | c | SDSF/CEMT close and open of CICS files around a VSAM reload; the target has no CICS region or VSAM files to hand over. |
| DEF-JXREF-003 | `jobs/XREFFILE.md (nl/G)` | c | VSAM alternate-index record size limits a cross-reference index record to 2 cards; a SQL index has no such limit, and the business limit is decided under Q-JXREF-001. |
| ERR-JXREF-001 | `jobs/XREFFILE.md (nl/G)` | c | IDCAMS delete/define return-code handling and step COND logic in a JCL reload job; the target loads data through its own batch framework with transactional steps, so it does not depend on this. |
| Q-INTC-002 | `programs/CBACT04C.md` | c | How z/OS VSAM reacts to two cards per account in the alternate index is platform behaviour; the target reads accounts and cards from SQL tables. |
| DEF-EXPT-002 | `programs/CBEXPORT.md` | c | The VSAM key offset in the IDCAMS define is a JCL utility step the target replaces; the target does not store the export in a VSAM cluster. |
| DEF-EXPT-005 | `programs/CBEXPORT.md` | c | The abend call without parameters is a z/OS Language Environment service quirk; the target job reports failure through its own exit status. |
| ERR-EXPT-004 | `programs/CBEXPORT.md` | c | The abend code from calling the LE abend service without arguments is z/OS-specific; the target only needs the run to stop with a non-zero result, which is already must-preserve. |
| Q-EXPT-002 | `programs/CBEXPORT.md` | c | The VSAM key offset in the IDCAMS define is a JCL utility step the target replaces; the spec states the target does not depend on the answer. |
| Q-EXPT-006 | `programs/CBEXPORT.md` | c | Which z/OS abend code a failed export ends with is platform-specific; the target defines its own failure exit status. |
| DEF-IMPT-003 | `programs/CBIMPORT.md` | c | The abend call without arguments is a z/OS Language Environment quirk; the target job reports failure through its own exit status. |
| DEF-IMPT-004 | `programs/CBIMPORT.md` | c | The VSAM cluster key offset mismatch is a JCL/IDCAMS definition issue; the target does not store the export in a VSAM cluster. |
| Q-IMPT-003 | `programs/CBIMPORT.md` | c | VSAM key offsets and the LE abend service are z/OS behaviour; the spec states the target does not depend on the answer. |
| DEF-AUPG-005 | `programs/CBPAUP0C.md` | c | Checkpoint frequency of an IMS BMP; the target sets its own commit interval in Spring Batch with no business effect. |
| Q-AUPG-003 | `programs/CBPAUP0C.md` | c | IMS checkpoint and database position behaviour; the target purge uses SQL queries, not DL/I navigation. |
| Q-AUPG-004 | `programs/CBPAUP0C.md` | c | IMS DLET call sequencing on a PCB; the target uses SQL deletes. |
| Q-AUPG-007 | `programs/CBPAUP0C.md` | c | IMS checkpoint and display frequency comparison; the target sets its own commit interval and progress logging. |
| Q-STMT-006 | `programs/CBSTM03A.md` | c | The behaviour of the LE abend service called without arguments and z/OS dataset disposition is platform behaviour the target replaces. |
| Q-STMT-008 | `programs/CBSTM03A.md` | c | z/OS control blocks, DFSORT padding bytes and JCL comment parsing do not carry over; the spec states the target does not depend on the answer. |
| DEF-STIO-001 | `programs/CBSTM03B.md` | c | The unchecked key length is an artifact of the COBOL file-access subroutine interface; the target uses typed repository calls with no length parameter. |
| DEF-STIO-004 | `programs/CBSTM03B.md` | c | Uninitialised file status before the first I/O is a COBOL storage and compiler artifact; the target data access has no such status field. |
| Q-STIO-002 | `programs/CBSTM03B.md` | c | The content of COBOL file status fields before any I/O depends on z/OS compiler and LE options; the target has no such fields. |
| Q-STIO-004 | `programs/CBSTM03B.md` | c | Which VSAM file status z/OS returns for a missing DD, empty cluster or implicit verify is platform behaviour the target replaces. |
| Q-POST-005 | `programs/CBTRN02C.md` | c | Confirming whether opening a non-empty TRANSACT for output fails on z/OS is VSAM REUSE behaviour; the target writes to a database table and does not depend on it. |
| DEF-MQAC-008 | `programs/COACCT01.md` | c | A loop when an IBM MQ close call fails is CICS/MQ API plumbing; RabbitMQ client connection handling in the target replaces it. |
| ERR-MQAC-008 | `programs/COACCT01.md` | c | Error records and re-entry on MQ close failure are IBM MQ API handling the RabbitMQ client replaces. |
| Q-MQAC-001 | `programs/COACCT01.md` | c | The IBM MQ trigger queue and process definition are platform objects; the target consumes from RabbitMQ directly. |
| Q-MQAC-004 | `programs/COACCT01.md` | c | How the CICS trigger monitor passes its message structure is IBM MQ/CICS plumbing the RabbitMQ consumer does not use. |
| Q-MQAC-005 | `programs/COACCT01.md` | c | Initial content of uninitialised working storage is Enterprise COBOL/LE behaviour; the target initialises its own variables. |
| DEF-ACUP-105 | `programs/COACTUPC.md` | c | The CSUTLDTC/CEEDAYS parameter-length mismatch is an LE call detail; the target replaces it with standard date parsing. |
| Q-ACUP-007 | `programs/COACTUPC.md` | c | Mainframe-only confirmations (alternate index DUPKEY, commarea overread, uninitialised record areas); the target uses SQL lookups and explicit state, and the behaviours are decided under account-lookup-card-xref and fix-obvious-defects. |
| Q-ACUP-109 | `programs/COACTUPC.md` | c | Mainframe-only question about LE CEEDAYS parameter handling and a data exception; the target uses its own date validation. |
| Q-ACVW-007 | `programs/COACTVWC.md` | c | BMS assembler handling of an unnamed field and terminal MUSTFILL enforcement are 3270 platform details the web UI does not use. |
| DEF-DTFT-001 | `programs/COBDATFT.md` | c | The 20-byte separator compare in the COBDATFT assembler routine depends on the HLASM literal pool; no caller sends type 1 and the target formats dates natively. |
| DEF-DTFT-002 | `programs/COBDATFT.md` | c | The garbage tail of the COBDATFT error message comes from assembler storage layout; no caller reads the message and the target does not reuse the routine. |
| DEF-DTFT-003 | `programs/COBDATFT.md` | c | The missing content validation in the COBDATFT assembler routine has no business effect because its only caller strips dashes from a known-good date; the target does this natively. |
| FLOW-DTFT-004 | `programs/COBDATFT.md` | c | This describes how the local GnuCOBOL shim differs from the assembler routine; it is a test stand-in, and the target replaces both. |
| VAL-DTFT-001 | `programs/COBDATFT.md` | c | The COBDATFT invalid-request message and its undefined tail are internal to an assembler routine that no caller checks; the target replaces it with native date formatting. |
| Q-DTFT-001 | `programs/COBDATFT.md` | c | Confirming the literal-pool layout on z/OS is a mainframe-only question; no caller uses type 1 or reads the message, so the target needs neither the assembler nor the shim behaviour. |
| Q-DTFT-002 | `programs/COBDATFT.md` | c | Whether to keep COBDATFT is a platform question: its only caller removes two dashes from a date, which the target does in place with no business effect. |
| Q-BILL-005 | `programs/COBIL00C.md` | c | What CICS STARTBR/READPREV return on an empty VSAM file and whether the alternate index returns DUPKEY are VSAM/CICS questions; the target uses database queries. |
| Q-CRDL-005 | `programs/COCRDLIC.md` | c | Mainframe confirmation of commarea size, CICS browse and cursor behaviour and NUMPROC compare; the target replaces CICS, VSAM browse and the compiler, so its paging and validation are specified directly. |
| Q-CRDV-005 | `programs/COCRDSLC.md` | c | Mainframe confirmation of Enterprise COBOL data-exception, commarea overrun, BMS MAPFAIL and working-storage behaviour; the target has none of these platform mechanisms. |
| Q-CRDU-006 | `programs/COCRDUPC.md` | c | Mainframe confirmation of commarea overrun, compiler handling of redefinitions, TRIM and BMS fill; the target replaces CICS and the compiler and validates input explicitly. |
| ERR-MQDT-007 | `programs/CODATE01.md` | c | Error records and re-entry on MQ close failure are IBM MQ API handling the RabbitMQ client replaces. |
| Q-MQDT-001 | `programs/CODATE01.md` | c | The IBM MQ trigger queue and process definition are platform objects; the target consumes from RabbitMQ directly. |
| Q-MENU-002 | `programs/COMEN01C.md` | c | How Enterprise COBOL normalises a non-digit menu option is compiler behaviour; the target validates the option as a number itself. |
| Q-AUTH-006 | `programs/COPAUA0C.md` | c | MQ trigger type and CICS process definition; the target consumes from RabbitMQ with a listener. |
| Q-AUSM-003 | `programs/COPAUS0C.md` | c | BMS mapset assembly and 3270 field order; the target screens are React. |
| Q-AUDT-004 | `programs/COPAUS1C.md` | c | CICS Db2 entry and plan name; the target uses a SQL Server connection. |
| Q-AUFR-002 | `programs/COPAUS2C.md` | c | CICS translator acceptance of a duplicated option; the target has no CICS translator. |
| BR-RPTS-006 | `programs/CORPT00C.md` | c | The 17-record JCL job stream is a z/OS submission mechanism; the target keeps only the dates and selection, which need no decision. |
| DEF-RPTS-004 | `programs/CORPT00C.md` | c | A partial job stream left in the JES internal reader after a write error is z/OS behaviour; the target starts the report job through its own scheduler, not by writing JCL. |
| DEF-RPTS-005 | `programs/CORPT00C.md` | c | Interleaved writes to the CICS internal-reader queue and duplicate JES job names are platform behaviour; the target submits report runs through its own job API. |
| DEF-RPTS-008 | `programs/CORPT00C.md` | c | The hard-coded job card, procedure library and DD names are JCL environment details; the target configures the report job outside the code. |
| Q-RPTS-003 | `programs/CORPT00C.md` | c | Internal-reader DD, partial writes, interleaving and duplicate job names are JES and CICS questions; the target does not submit JCL. |
| Q-RPTS-005 | `programs/CORPT00C.md` | d | Answered in the TRANREPT-PROC job spec: the cataloged member is found through JCLLIB and the REPROC label is ignored. Answered in jobs/TRANREPT-PROC.md (nl/G), "Answer to Q-RPTS-005" and DEF-JTRPTP-001. |
| DEF-SGN-004 | `programs/COSGN00C.md` | c | Sending the typed password back in the 3270 output map is BMS data-stream behaviour; a web form keeps typed values in the browser and the server need not return the password. |
| Q-SGN-006 | `programs/COSGN00C.md` | c | CICS terminal upper-case translation and the LE storage option are region settings; the target decides case handling itself in the password-storage decision. |
| Q-TRNL-003 | `programs/COTRN00C.md` | c | Whether ENDBR after NOTFND abends, EIBAID survives an XCTL, and READPREV after STARTBR on a missing key are CICS questions; the target uses database queries and its own navigation. |
| DEF-TRNV-001 | `programs/COTRN01C.md` | c | The read-for-update lock on a display-only read is CICS/VSAM locking; the target reads the transaction without a lock. |
| Q-TRNV-003 | `programs/COTRN01C.md` | c | How long a CICS READ UPDATE lock is held on a non-recoverable VSAM file is a platform question; the target does not lock for a display. |
| Q-TRNA-004 | `programs/COTRN02C.md` | c | DUPKEY on the VSAM alternate index and STARTBR/READPREV on an empty file are VSAM/CICS questions; the target uses database queries, and the multi-card behaviour is decided under DEF-TRNA-004. |
| Q-TTLS-005 | `programs/COTRTLIC.md` | c | TRIM length, terminal upper-case translation, the Db2 SQLCODE and DSNTIAC text are compiler, CICS and Db2 specifics the target replaces. |
| Q-TTUP-002 | `programs/COTRTUPC.md` | c | FUNCTION TRIM and NUMVAL results on Enterprise COBOL are compiler behaviour; the target implements its own validation. |
| Q-USRL-003 | `programs/COUSR00C.md` | c | CICS STARTBR positioning and ENDBR abend behaviour are platform questions; the target pages with SQL queries. |
| DEF-USRA-006 | `programs/COUSR01C.md` | c | Returning the password in the 3270 output map after an error is BMS data-stream behaviour; a web form keeps typed values client-side and the server need not return the password. |
| DEF-USRU-010 | `programs/COUSR02C.md` | c | A possible self-wait on the user file string after XCTL is CICS LSR string behaviour; the target has no CICS file strings. |
| DEF-DTCK-003 | `programs/CSUTLDTC.md` | c | How LE CEEDAYS treats the two extra bytes passed by the account update is LE behaviour; the target validates the 8-digit date with its own date library. |
| Q-DTCK-001 | `programs/CSUTLDTC.md` | c | Which separators and one-digit parts IBM LE CEEDAYS accepts is LE behaviour; the target validates the YYYY-MM-DD format with its own date library. |
| Q-DTCK-002 | `programs/CSUTLDTC.md` | c | What CEEDAYS returns for the malformed account update call is LE behaviour; the target does not call CEEDAYS. |
| Q-DTCK-004 | `programs/CSUTLDTC.md` | c | Whether CSUTLDTC is statically or dynamically linked is a z/OS link-edit question; the target uses a library call. |
| DEF-DBUL-001 | `programs/DBUNLDGS.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this GSAM detail record layout does not carry over. |
| DEF-DBUL-002 | `programs/DBUNLDGS.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this unload skip reporting does not carry over. |
| Q-DBUL-001 | `programs/DBUNLDGS.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this question of what reads the GSAM files is mainframe-only. |
| Q-DBUL-002 | `programs/DBUNLDGS.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this handling of skipped summaries in the unload does not carry over. |
| Q-MWAT-001 | `programs/MVSWAIT.md` | c | The X'FF' return flag in the caller save area is a z/OS linkage convention; the spec states the target does not depend on it. |
| Q-MWAT-002 | `programs/MVSWAIT.md` | c | Link-edit attributes (RENT, AMODE, RMODE) and static or dynamic call binding are z/OS load-module properties with no target equivalent. |
| DEF-PDLD-001 | `programs/PAUDBLOD.md` | c | IMS PCB list and link-edit entry point; the target has no PCBs. |
| DEF-PDLD-002 | `programs/PAUDBLOD.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this missing-summary handling in the IMS load program does not carry over. |
| DEF-PDLD-004 | `programs/PAUDBLOD.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this IMS load checkpointing does not carry over. |
| ERR-PDLD-002 | `programs/PAUDBLOD.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this read-error loop in the IMS load program does not carry over. |
| Q-PDLD-001 | `programs/PAUDBLOD.md` | c | IMS link-edit entry point question; the target has no PCBs. |
| Q-PDLD-002 | `programs/PAUDBLOD.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this question of reporting a detail with a missing summary in the IMS load does not carry over. |
| Q-PDLD-003 | `programs/PAUDBLOD.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this question of checkpoints and backout in the IMS load does not carry over. |
| DEF-PDUL-001 | `programs/PAUDBUNL.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this unchecked write status in the IMS unload does not carry over. |
| DEF-PDUL-002 | `programs/PAUDBUNL.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this unreported skipped summaries in the IMS unload do not carry over. |
| DEF-PDUL-003 | `programs/PAUDBUNL.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this catalogued partial unload files do not carry over. |
| DEF-PDUL-005 | `programs/PAUDBUNL.md` | c | Depends on z/OS QSAM record-area behaviour after WRITE in the IMS unload, which the target does not use. |
| Q-PDUL-001 | `programs/PAUDBUNL.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this question of how unload errors end the step does not carry over. |
| Q-PDUL-002 | `programs/PAUDBUNL.md` | c | The target replaces the IMS unload and reload utilities with SQL Server tables and its own backup and migration, so this question of reporting skipped summaries in the unload does not carry over. |
| Q-PDUL-003 | `programs/PAUDBUNL.md` | c | Asks whether the compiled z/OS program keeps the record area after WRITE, which the target does not depend on. |

## Appendix C. Index of decision items by spec

Every (a) and (b) item, grouped by the spec that holds it, with its decision.

| Spec | Items (decision) |
| :-- | :-- |
| `entities.md` | Q-ENT-001 (D-11), Q-ENT-002 (D-59) |
| `jobs/ACCTFILE.md (nl/G)` | Q-JACCT-001 (D-41) |
| `jobs/CARDDEMO-CSD.md (nl/G)` | DEF-JCSD-003 (D-02), DEF-JCSD-004 (D-07), ERR-JCSD-001 (D-16), Q-JCSD-001 (D-02), Q-JCSD-002 (D-16) |
| `jobs/CARDFILE.md (nl/G)` | Q-JCARD-001 (D-61), Q-JCARD-002 (D-41) |
| `jobs/CBEXPORT-JOB.md (nl/G)` | DEF-JEXPT-001 (D-62), DEF-JEXPT-002 (D-10), Q-JEXPT-001 (D-62), Q-JEXPT-002 (D-10) |
| `jobs/CBIMPORT-JOB.md (nl/G)` | DEF-JIMPT-002 (D-10), DEF-JIMPT-003 (D-62), ERR-JIMPT-002 (D-75), Q-JIMPT-001 (D-62) |
| `jobs/CBPAUP0J.md` | BR-JAUPG-001 (D-34), DEF-JAUPG-001 (D-34), DEF-JAUPG-003 (D-57), ERR-JAUPG-001 (D-57), Q-JAUPG-001 (D-57), Q-JAUPG-002 (D-39) |
| `jobs/CLOSEFIL.md (nl/G)` | DEF-JCLOSE-003 (D-18), Q-JCLOSE-001 (D-18) |
| `jobs/COMBTRAN.md (nl/G)` | DEF-JCOMB-002 (D-37) |
| `jobs/CRDDEMO2-CSD.md` | DEF-JCSD2-001 (D-03) |
| `jobs/CRDDEMOD-CSD.md` | DEF-JCSDD-001 (D-02) |
| `jobs/CRDDEMOM-CSD.md` | DEF-JCSDM-003 (D-07) |
| `jobs/CREADB21.md` | DEF-JCRDB-002 (D-75), DEF-JCRDB-003 (D-07), DEF-JCRDB-005 (D-63), Q-JCRDB-001 (D-75), Q-JCRDB-003 (D-07), Q-JCRDB-004 (D-63) |
| `jobs/CREASTMT.md (nl/G)` | DEF-JSTMT-001 (D-57), DEF-JSTMT-005 (D-43), DEF-JSTMT-006 (D-38), Q-JSTMT-001 (D-38), Q-JSTMT-002 (D-43), Q-JSTMT-003 (D-57) |
| `jobs/CUSTFILE.md (nl/G)` | Q-JCUST-001 (D-41) |
| `jobs/DALYREJS.md (nl/G)` | DEF-JREJS-003 (D-10), Q-JREJS-001 (D-40) |
| `jobs/DEFGDGB.md (nl/G)` | DEF-JGDGB-002 (D-10), Q-JGDGB-001 (D-40) |
| `jobs/DEFGDGD.md (nl/G)` | Q-JGDGD-001 (D-63) |
| `jobs/DISCGRP.md (nl/G)` | Q-JDISC-001 (D-67) |
| `jobs/DUSRSECJ.md (nl/G)` | DEF-JUSEC-001 (D-04), Q-JUSEC-001 (D-06), Q-JUSEC-002 (D-06) |
| `jobs/ESDSRRDS.md (nl/G)` | DEF-JESRR-001 (D-07), Q-JESRR-001 (D-42) |
| `jobs/FTPJCL.md (nl/G)` | DEF-JFTP-004 (D-07), Q-JFTP-001 (D-42) |
| `jobs/INTCALC.md (nl/G)` | DEF-JINTC-001 (D-59), ERR-JINTC-001 (D-57), NUM-JINTC-001 (D-59), Q-JINTC-001 (D-59), Q-JINTC-002 (D-24) |
| `jobs/INTRDRJ1.md (nl/G)` | Q-JIRD1-001 (D-42) |
| `jobs/MNTTRDB2.md` | Q-JMNTT-001 (D-63), Q-JMNTT-002 (D-63) |
| `jobs/POSTTRAN.md (nl/G)` | DEF-JPOST-002 (D-37), DEF-JPOST-003 (D-23), Q-JPOST-001 (D-40) |
| `jobs/PRTCATBL.md (nl/G)` | DEF-JPCAT-001 (D-75), Q-JPCAT-001 (D-75) |
| `jobs/READACCT.md (nl/G)` | DEF-JRACCT-002 (D-10), DEF-JRACCT-005 (D-37), Q-JRACCT-001 (D-42) |
| `jobs/READCARD.md (nl/G)` | DEF-JRCARD-001 (D-10), Q-JRCARD-001 (D-42) |
| `jobs/READCUST.md (nl/G)` | DEF-JRCUST-001 (D-10), Q-JRCUST-001 (D-42) |
| `jobs/READXREF.md (nl/G)` | DEF-JRXREF-001 (D-10), Q-JRXREF-001 (D-42) |
| `jobs/REPTFILE.md (nl/G)` | Q-JREPT-001 (D-40) |
| `jobs/SCHED-CA7.md (nl/G)` | DEF-JCA7-005 (D-37), DEF-JCA7-007 (D-39), Q-JCA7-001 (D-37), Q-JCA7-003 (D-39), Q-JCA7-004 (D-42) |
| `jobs/SCHED-CONTROLM.md (nl/G)` | DEF-JCTLM-001 (D-37), DEF-JCTLM-002 (D-24), DEF-JCTLM-005 (D-63), DEF-JCTLM-006 (D-37), Q-JCTLM-001 (D-37), Q-JCTLM-002 (D-24), Q-JCTLM-003 (D-57), Q-JCTLM-004 (D-37) |
| `jobs/TCATBALF.md (nl/G)` | DEF-JTCAT-002 (D-41), Q-JTCAT-001 (D-41) |
| `jobs/TRANBKP.md (nl/G)` | DEF-JTBKP-001 (D-57), Q-JTBKP-001 (D-57), Q-JTBKP-002 (D-40) |
| `jobs/TRANCATG.md (nl/G)` | Q-JTCATG-001 (D-63) |
| `jobs/TRANEXTR.md` | DEF-JTREX-001 (D-63), Q-JTREX-001 (D-63), Q-JTREX-002 (D-63) |
| `jobs/TRANFILE.md (nl/G)` | DEF-JTRAN-005 (D-59), Q-JTRAN-001 (D-59) |
| `jobs/TRANIDX.md (nl/G)` | DEF-JTIDX-004 (D-42), Q-JTIDX-001 (D-42) |
| `jobs/TRANREPT-PROC.md (nl/G)` | DEF-JTRPTP-004 (D-10), DEF-JTRPTP-005 (D-07), ERR-JTRPTP-001 (D-48), Q-JTRPTP-001 (D-48) |
| `jobs/TRANREPT.md (nl/G)` | BR-JTRPT-001 (D-46), DEF-JTRPT-001 (D-46), DEF-JTRPT-004 (D-10), DEF-JTRPT-005 (D-18), SE-JTRPT-002 (D-75), Q-JTRPT-001 (D-42) |
| `jobs/TRANTYPE.md (nl/G)` | Q-JTTYP-001 (D-63) |
| `jobs/TXT2PDF1.md (nl/G)` | DEF-JPDF-001 (D-45), DEF-JPDF-003 (D-10), Q-JPDF-001 (D-45), Q-JPDF-002 (D-45) |
| `jobs/XREFFILE.md (nl/G)` | Q-JXREF-001 (D-61), Q-JXREF-002 (D-41) |
| `programs/CBACT01C.md` | BR-ACRD-005 (D-42), BR-ACRD-006 (D-42), DEF-ACRD-002 (D-42), Q-ACRD-001 (D-42), Q-ACRD-002 (D-42) |
| `programs/CBACT02C.md` | DEF-CDRD-001 (D-10), Q-CDRD-001 (D-10) |
| `programs/CBACT03C.md` | DEF-XRRD-001 (D-10), Q-XRRD-001 (D-10) |
| `programs/CBACT04C.md` | BR-INTC-001 (D-25), DEF-INTC-004 (D-26), DEF-INTC-005 (D-23), DEF-INTC-010 (D-59), DEF-INTC-011 (D-10), DEF-INTC-012 (D-25), Q-INTC-001 (D-11), Q-INTC-003 (D-25), Q-INTC-004 (D-25), Q-INTC-005 (D-23), Q-INTC-006 (D-59), Q-INTC-007 (D-26) |
| `programs/CBCUS01C.md` | DEF-CURD-001 (D-10), Q-CURD-001 (D-10) |
| `programs/CBEXPORT.md` | DEF-EXPT-003 (D-60), DEF-EXPT-007 (D-62), DEF-EXPT-009 (D-10), DEF-EXPT-010 (D-10), NUM-EXPT-001 (D-60), Q-EXPT-001 (D-60), Q-EXPT-003 (D-10), Q-EXPT-004 (D-62), Q-EXPT-005 (D-62) |
| `programs/CBIMPORT.md` | BR-IMPT-011 (D-57), DEF-IMPT-001 (D-62), DEF-IMPT-002 (D-75), DEF-IMPT-008 (D-10), DEF-IMPT-009 (D-62), ERR-IMPT-004 (D-57), VAL-IMPT-001 (D-62), Q-IMPT-001 (D-62), Q-IMPT-002 (D-57), Q-IMPT-004 (D-62) |
| `programs/CBPAUP0C.md` | BR-AUPG-001 (D-34), BR-AUPG-003 (D-33), BR-AUPG-004 (D-34), DEF-AUPG-001 (D-34), DEF-AUPG-002 (D-34), DEF-AUPG-003 (D-33), DEF-AUPG-004 (D-34), DEF-AUPG-006 (D-57), DEF-AUPG-008 (D-10), DEF-AUPG-009 (D-34), Q-AUPG-001 (D-34), Q-AUPG-002 (D-33), Q-AUPG-005 (D-34), Q-AUPG-006 (D-34), Q-AUPG-008 (D-57), Q-AUPG-009 (D-10) |
| `programs/CBSTM03A.md` | BR-STMT-005 (D-44), BR-STMT-006 (D-44), BR-STMT-008 (D-44), DEF-STMT-001 (D-75), DEF-STMT-002 (D-44), DEF-STMT-003 (D-44), DEF-STMT-004 (D-75), DEF-STMT-005 (D-43), DEF-STMT-007 (D-45), DEF-STMT-008 (D-10), DEF-STMT-010 (D-75), Q-STMT-001 (D-43), Q-STMT-002 (D-43), Q-STMT-003 (D-44), Q-STMT-004 (D-10), Q-STMT-005 (D-75), Q-STMT-007 (D-45) |
| `programs/CBSTM03B.md` | BR-STIO-004 (D-75), DEF-STIO-002 (D-75), DEF-STIO-003 (D-75), DEF-STIO-005 (D-10), Q-STIO-001 (D-75), Q-STIO-003 (D-75) |
| `programs/CBTRN01C.md` | BR-TVAL-005 (D-42), DEF-TVAL-002 (D-10), DEF-TVAL-004 (D-42), ERR-TVAL-003 (D-57), Q-TVAL-001 (D-42), Q-TVAL-002 (D-42), Q-TVAL-003 (D-42) |
| `programs/CBTRN02C.md` | BR-POST-001 (D-19), DEF-POST-003 (D-20), DEF-POST-005 (D-19), DEF-POST-011 (D-10), DEF-POST-012 (D-20), Q-POST-001 (D-19), Q-POST-002 (D-21), Q-POST-003 (D-20), Q-POST-004 (D-22) |
| `programs/CBTRN03C.md` | DEF-TRPT-001 (D-46), DEF-TRPT-004 (D-47), DEF-TRPT-006 (D-10), FLOW-TRPT-001 (D-46), Q-TRPT-001 (D-46), Q-TRPT-002 (D-47), Q-TRPT-003 (D-47) |
| `programs/COACCT01.md` | DEF-MQAC-001 (D-72), DEF-MQAC-002 (D-73), DEF-MQAC-003 (D-73), DEF-MQAC-004 (D-07), DEF-MQAC-007 (D-74), DEF-MQAC-014 (D-72), ERR-MQAC-005 (D-74), Q-MQAC-002 (D-73), Q-MQAC-003 (D-07), Q-MQAC-006 (D-74), Q-MQAC-007 (D-72), Q-MQAC-008 (D-72) |
| `programs/COACTUPC.md` | BR-ACUP-004 (D-75), BR-ACUP-006 (D-11), BR-ACUP-102 (D-50), DEF-ACUP-001 (D-03), DEF-ACUP-002 (D-11), DEF-ACUP-003 (D-14), DEF-ACUP-004 (D-75), DEF-ACUP-005 (D-16), DEF-ACUP-006 (D-17), DEF-ACUP-007 (D-75), DEF-ACUP-009 (D-01), DEF-ACUP-010 (D-75), DEF-ACUP-012 (D-61), DEF-ACUP-013 (D-01), DEF-ACUP-016 (D-09), DEF-ACUP-019 (D-75), DEF-ACUP-020 (D-11), DEF-ACUP-101 (D-75), DEF-ACUP-104 (D-75), DEF-ACUP-106 (D-75), DEF-ACUP-107 (D-75), DEF-ACUP-109 (D-50), DEF-ACUP-110 (D-50), DEF-ACUP-111 (D-50), DEF-ACUP-112 (D-49), DEF-ACUP-113 (D-50), DEF-ACUP-114 (D-08), DEF-ACUP-115 (D-50), DEF-ACUP-118 (D-50), DEF-ACUP-120 (D-50), DEF-ACUP-122 (D-75), ERR-ACUP-002 (D-75), ERR-ACUP-004 (D-16), ERR-ACUP-102 (D-75), NUM-ACUP-003 (D-75), NUM-ACUP-005 (D-14), NUM-ACUP-010 (D-11), NUM-ACUP-101 (D-50), SE-ACUP-006 (D-11), SE-ACUP-007 (D-16), VAL-ACUP-001 (D-75), VAL-ACUP-004 (D-50), VAL-ACUP-005 (D-50), VAL-ACUP-019 (D-50), VAL-ACUP-021 (D-50), VAL-ACUP-026 (D-50), VAL-ACUP-033 (D-50), Q-ACUP-001 (D-11), Q-ACUP-002 (D-16), Q-ACUP-003 (D-17), Q-ACUP-004 (D-03), Q-ACUP-005 (D-14), Q-ACUP-006 (D-01), Q-ACUP-008 (D-75), Q-ACUP-009 (D-75), Q-ACUP-010 (D-11), Q-ACUP-101 (D-49), Q-ACUP-102 (D-50), Q-ACUP-103 (D-08), Q-ACUP-104 (D-50), Q-ACUP-105 (D-50), Q-ACUP-106 (D-50), Q-ACUP-107 (D-50), Q-ACUP-108 (D-50), Q-ACUP-110 (D-75) |
| `programs/COACTVWC.md` | DEF-ACVW-002 (D-75), DEF-ACVW-003 (D-61), DEF-ACVW-004 (D-61), DEF-ACVW-005 (D-75), DEF-ACVW-008 (D-03), DEF-ACVW-009 (D-01), DEF-ACVW-010 (D-09), DEF-ACVW-011 (D-01), DEF-ACVW-012 (D-75), DEF-ACVW-016 (D-58), DEF-ACVW-017 (D-14), DEF-ACVW-018 (D-75), ERR-ACVW-001 (D-75), NUM-ACVW-004 (D-14), NUM-ACVW-005 (D-58), VAL-ACVW-003 (D-58), VAL-ACVW-004 (D-75), VAL-ACVW-006 (D-75), Q-ACVW-001 (D-03), Q-ACVW-002 (D-09), Q-ACVW-003 (D-61), Q-ACVW-004 (D-75), Q-ACVW-005 (D-61), Q-ACVW-006 (D-75), Q-ACVW-008 (D-58), Q-ACVW-009 (D-75) |
| `programs/COADM01C.md` | BR-ADMN-003 (D-75), DEF-ADMN-001 (D-02), DEF-ADMN-006 (D-75), Q-ADMN-001 (D-02), Q-ADMN-002 (D-70), Q-ADMN-003 (D-70) |
| `programs/COBIL00C.md` | DEF-BILL-001 (D-29), DEF-BILL-009 (D-03), DEF-BILL-010 (D-28), DEF-BILL-014 (D-30), Q-BILL-001 (D-30), Q-BILL-002 (D-28), Q-BILL-003 (D-29), Q-BILL-004 (D-03), Q-BILL-006 (D-60) |
| `programs/COBSWAIT.md` | DEF-WAIT-001 (D-37), NUM-WAIT-001 (D-37), Q-WAIT-001 (D-37), Q-WAIT-002 (D-37) |
| `programs/COBTUPDT.md` | DEF-TTBU-001 (D-57), DEF-TTBU-003 (D-65), DEF-TTBU-009 (D-02), Q-TTBU-001 (D-57), Q-TTBU-002 (D-65), Q-TTBU-003 (D-57), Q-TTBU-004 (D-57), Q-TTBU-005 (D-66) |
| `programs/COCRDLIC.md` | BR-CRDL-002 (D-03), BR-CRDL-004 (D-54), DEF-CRDL-001 (D-03), DEF-CRDL-002 (D-09), DEF-CRDL-003 (D-01), DEF-CRDL-004 (D-01), DEF-CRDL-005 (D-01), DEF-CRDL-006 (D-54), DEF-CRDL-007 (D-55), DEF-CRDL-008 (D-75), DEF-CRDL-009 (D-75), DEF-CRDL-010 (D-54), DEF-CRDL-018 (D-55), DEF-CRDL-019 (D-75), FLOW-CRDL-001 (D-01), FLOW-CRDL-008 (D-01), NUM-CRDL-001 (D-54), SE-CRDL-003 (D-01), Q-CRDL-001 (D-03), Q-CRDL-002 (D-09), Q-CRDL-003 (D-54), Q-CRDL-004 (D-01), Q-CRDL-006 (D-54), Q-CRDL-007 (D-55), Q-CRDL-008 (D-01), Q-CRDL-009 (D-69) |
| `programs/COCRDSLC.md` | DEF-CRDV-001 (D-13), DEF-CRDV-002 (D-03), DEF-CRDV-003 (D-01), DEF-CRDV-004 (D-01), DEF-CRDV-005 (D-69), DEF-CRDV-007 (D-75), DEF-CRDV-008 (D-75), DEF-CRDV-011 (D-09), DEF-CRDV-012 (D-75), DEF-CRDV-014 (D-69), VAL-CRDV-008 (D-75), Q-CRDV-001 (D-13), Q-CRDV-002 (D-03), Q-CRDV-003 (D-01), Q-CRDV-004 (D-69), Q-CRDV-006 (D-09) |
| `programs/COCRDUPC.md` | BR-CRDU-002 (D-13), BR-CRDU-006 (D-12), BR-CRDU-007 (D-13), BR-CRDU-008 (D-51), DEF-CRDU-001 (D-13), DEF-CRDU-002 (D-12), DEF-CRDU-003 (D-12), DEF-CRDU-004 (D-75), DEF-CRDU-006 (D-03), DEF-CRDU-007 (D-01), DEF-CRDU-008 (D-51), DEF-CRDU-009 (D-51), DEF-CRDU-012 (D-69), DEF-CRDU-019 (D-09), FLOW-CRDU-001 (D-75), NUM-CRDU-003 (D-51), NUM-CRDU-004 (D-12), Q-CRDU-001 (D-13), Q-CRDU-002 (D-12), Q-CRDU-003 (D-51), Q-CRDU-004 (D-51), Q-CRDU-005 (D-03), Q-CRDU-007 (D-68), Q-CRDU-008 (D-51) |
| `programs/CODATE01.md` | DEF-MQDT-001 (D-72), DEF-MQDT-002 (D-73), DEF-MQDT-003 (D-73), DEF-MQDT-004 (D-72), DEF-MQDT-012 (D-72), Q-MQDT-002 (D-72), Q-MQDT-003 (D-72) |
| `programs/COMEN01C.md` | BR-MENU-003 (D-02), DEF-MENU-002 (D-02), DEF-MENU-003 (D-01), Q-MENU-001 (D-02), Q-MENU-003 (D-05) |
| `programs/COPAUA0C.md` | BR-AUTH-003 (D-31), BR-AUTH-004 (D-33), BR-AUTH-007 (D-32), BR-AUTH-008 (D-32), BR-AUTH-009 (D-31), DEF-AUTH-001 (D-31), DEF-AUTH-002 (D-31), DEF-AUTH-003 (D-31), DEF-AUTH-004 (D-31), DEF-AUTH-005 (D-33), DEF-AUTH-006 (D-32), DEF-AUTH-007 (D-32), DEF-AUTH-008 (D-35), DEF-AUTH-011 (D-10), DEF-AUTH-015 (D-31), FLOW-AUTH-003 (D-35), NUM-AUTH-003 (D-31), Q-AUTH-001 (D-31), Q-AUTH-002 (D-32), Q-AUTH-003 (D-33), Q-AUTH-004 (D-32), Q-AUTH-005 (D-35) |
| `programs/COPAUS0C.md` | BR-AUSM-006 (D-54), DEF-AUSM-002 (D-54), DEF-AUSM-007 (D-03), DEF-AUSM-010 (D-61), DEF-AUSM-011 (D-54), DEF-AUSM-012 (D-01), Q-AUSM-001 (D-54), Q-AUSM-002 (D-03), Q-AUSM-004 (D-61), Q-AUSM-005 (D-01) |
| `programs/COPAUS1C.md` | DEF-AUDT-001 (D-75), DEF-AUDT-002 (D-75), DEF-AUDT-003 (D-75), DEF-AUDT-005 (D-75), DEF-AUDT-006 (D-02), DEF-AUDT-009 (D-01), Q-AUDT-001 (D-75), Q-AUDT-002 (D-75), Q-AUDT-003 (D-02), Q-AUDT-005 (D-01) |
| `programs/COPAUS2C.md` | DEF-AUFR-001 (D-36), NUM-AUFR-002 (D-36), Q-AUFR-001 (D-36) |
| `programs/CORPT00C.md` | DEF-RPTS-001 (D-52), DEF-RPTS-002 (D-52), DEF-RPTS-003 (D-75), DEF-RPTS-006 (D-48), DEF-RPTS-007 (D-03), Q-RPTS-001 (D-48), Q-RPTS-002 (D-52), Q-RPTS-004 (D-03) |
| `programs/COSGN00C.md` | BR-SGN-003 (D-04), DEF-SGN-001 (D-04), DEF-SGN-002 (D-05), DEF-SGN-003 (D-05), DEF-SGN-005 (D-02), DEF-SGN-006 (D-01), DEF-SGN-011 (D-04), DEF-SGN-012 (D-01), Q-SGN-001 (D-04), Q-SGN-002 (D-04), Q-SGN-003 (D-05), Q-SGN-004 (D-05), Q-SGN-005 (D-02) |
| `programs/COTRN00C.md` | BR-TRNL-006 (D-03), DEF-TRNL-002 (D-75), DEF-TRNL-003 (D-75), DEF-TRNL-005 (D-54), DEF-TRNL-006 (D-52), DEF-TRNL-007 (D-03), DEF-TRNL-008 (D-54), DEF-TRNL-011 (D-55), DEF-TRNL-012 (D-75), Q-TRNL-001 (D-03), Q-TRNL-002 (D-54), Q-TRNL-004 (D-75) |
| `programs/COTRN01C.md` | DEF-TRNV-002 (D-75), DEF-TRNV-003 (D-52), DEF-TRNV-004 (D-09), DEF-TRNV-006 (D-71), Q-TRNV-001 (D-09), Q-TRNV-002 (D-71) |
| `programs/COTRN02C.md` | BR-TRNA-003 (D-59), BR-TRNA-007 (D-27), DEF-TRNA-001 (D-27), DEF-TRNA-002 (D-52), DEF-TRNA-003 (D-59), DEF-TRNA-004 (D-61), DEF-TRNA-005 (D-61), DEF-TRNA-006 (D-52), DEF-TRNA-007 (D-52), DEF-TRNA-008 (D-52), DEF-TRNA-009 (D-03), DEF-TRNA-012 (D-02), DEF-TRNA-014 (D-75), Q-TRNA-001 (D-27), Q-TRNA-002 (D-02), Q-TRNA-003 (D-59), Q-TRNA-005 (D-52) |
| `programs/COTRTLIC.md` | BR-TTLS-003 (D-65), BR-TTLS-008 (D-65), DEF-TTLS-001 (D-65), DEF-TTLS-002 (D-64), DEF-TTLS-003 (D-17), DEF-TTLS-004 (D-65), DEF-TTLS-005 (D-75), DEF-TTLS-006 (D-75), DEF-TTLS-007 (D-75), DEF-TTLS-008 (D-75), DEF-TTLS-009 (D-02), DEF-TTLS-014 (D-17), DEF-TTLS-016 (D-65), DEF-TTLS-017 (D-01), Q-TTLS-001 (D-65), Q-TTLS-002 (D-64), Q-TTLS-003 (D-02), Q-TTLS-004 (D-65), Q-TTLS-006 (D-65), Q-TTLS-007 (D-17) |
| `programs/COTRTUPC.md` | DEF-TTUP-001 (D-75), DEF-TTUP-002 (D-75), DEF-TTUP-003 (D-17), DEF-TTUP-007 (D-65), DEF-TTUP-010 (D-02), DEF-TTUP-017 (D-01), Q-TTUP-001 (D-17), Q-TTUP-003 (D-75), Q-TTUP-004 (D-65), Q-TTUP-005 (D-64), Q-TTUP-006 (D-02), Q-TTUP-007 (D-65) |
| `programs/COUSR00C.md` | DEF-USRL-001 (D-75), DEF-USRL-002 (D-54), DEF-USRL-004 (D-55), DEF-USRL-007 (D-54), DEF-USRL-008 (D-02), DEF-USRL-011 (D-54), DEF-USRL-012 (D-55), DEF-USRL-013 (D-02), DEF-USRL-014 (D-54), ERR-USRL-001 (D-75), VAL-USRL-003 (D-54), Q-USRL-001 (D-54), Q-USRL-002 (D-54) |
| `programs/COUSR01C.md` | BR-USRA-001 (D-53), DEF-USRA-001 (D-04), DEF-USRA-002 (D-53), DEF-USRA-003 (D-04), DEF-USRA-005 (D-02), DEF-USRA-009 (D-53), VAL-USRA-003 (D-75), Q-USRA-001 (D-53), Q-USRA-002 (D-56) |
| `programs/COUSR02C.md` | BR-USRU-005 (D-68), DEF-USRU-001 (D-68), DEF-USRU-002 (D-75), DEF-USRU-003 (D-15), DEF-USRU-004 (D-53), DEF-USRU-005 (D-04), DEF-USRU-006 (D-02), DEF-USRU-007 (D-17), DEF-USRU-011 (D-04), Q-USRU-001 (D-68), Q-USRU-002 (D-04), Q-USRU-003 (D-17) |
| `programs/COUSR03C.md` | BR-USRD-003 (D-15), DEF-USRD-001 (D-15), DEF-USRD-002 (D-75), DEF-USRD-004 (D-06), Q-USRD-001 (D-15), Q-USRD-002 (D-06), Q-USRD-003 (D-56) |
| `programs/CSUTLDTC.md` | DEF-DTCK-004 (D-52), Q-DTCK-003 (D-52) |

## Appendix D. Method

- **Sources:** branch `modernization` at `9540387` (all program specs, the area A-F job specs, `entities.md`, `components.md`, `interactions.md`, `areas/*.md`) and branch `nl/G` at `a609e82` (the area G job, CSD and schedule specs and `jobs/README.md`). Items are unioned by ID; where both branches have the same spec, the `modernization` copy is used.
- **Extraction:** every table row `| Q-<UNIT>-<NNN> |`, and every item heading `#### <KIND>-<UNIT>-<NNN>` whose `Fidelity` contains *defect-decide* or whose title is tagged `[security]`, in every spec outside `checks/`, `evidence/` and `tools/`. `areas/*.md`, `components.md` and `interactions.md` hold no `Q-` rows or defect-decide items of their own; their invariants (`INV-`, `PINV-`) are cited in the decisions.
- **Classification:** each item was read with its spec and classed (a) to (d), then items asking the same thing were merged into one decision across programs and areas. An item that says "Expected yes" is a recommendation, not an answer, so it stays a decision. The only answered item is Q-RPTS-005, answered in the TRANREPT-PROC job spec.
- **Pilot questions:** Q-BILL-001 → D-30; Q-BILL-002 → D-28; Q-BILL-003 → D-29; Q-BILL-004 → D-03; Q-BILL-005 → class c; Q-BILL-006 → D-60; Q-ENT-001 → D-11; Q-ENT-002 → D-59; Q-POST-001 → D-19; Q-POST-002 → D-21; Q-POST-003 → D-20; Q-POST-004 → D-22; Q-POST-005 → class c.
- **Default:** an undecided item keeps the reference COBOL behaviour, so the equivalence contracts in the specs stay valid until a decision changes them.

