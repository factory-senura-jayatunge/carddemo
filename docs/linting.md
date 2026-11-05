# Linting and static checks

`scripts/cobol_lint.py` checks the fixed-format COBOL programs, copybooks and JCL under `app/`, plus the COBOL shims and drivers of the local build in `tools/shims` and `tools/drivers`. `tools/templates/*.in` is excluded: those files are COBOL skeletons with `@PLACEHOLDER@` tokens that `tools/carddemo.py` fills in, so they are not valid COBOL before substitution. It is a single Python 3.10+ file that uses only the standard library. `cobol-lint.json` holds the paths, every rule's severity and thresholds, and the reason for each exemption.

## Commands

| Command | What it does |
|:--|:--|
| `scripts/lint.sh` | Same as `python3 scripts/cobol_lint.py check`: all lint rules plus the cobc syntax check. CI runs this. |
| `python3 scripts/cobol_lint.py lint [FILES]` | Lint rules only (no cobc). |
| `python3 scripts/cobol_lint.py syntax [FILES]` | `cobc -fsyntax-only` only. |
| `python3 scripts/cobol_lint.py report` | Counts per rule, the most complex paragraphs and programs, and tech-debt marker counts. Always exits 0. |
| `python3 scripts/cobol_lint.py rules` | Lists every rule with its severity and thresholds. |
| `python3 scripts/cobol_lint.py fix FILE... [--check]` | Formatter (see below). |
| `python3 scripts/cobol_lint.py baseline` | Rewrites `cobol-lint-baseline.json` from the current findings. |
| `python3 -m unittest discover -s scripts/tests` | Tests for the linter itself. |

Common options: `--no-baseline` shows every finding, `--rule ID` (repeatable) limits the output to one rule, and `--staged` checks the files staged in git. Each finding prints as `path:line: severity [rule-id] message`.

Exit codes: `0` means no new findings. `1` means there are new findings, or `fix --check` would change a file. `2` means a usage or configuration error, or cobc is missing for `check`/`syntax` (pass `--allow-missing-cobc` to downgrade a missing cobc to a warning).

## Baseline

The sources contained findings before the linter existed, and this change does not edit them. `cobol-lint-baseline.json` records those findings as path, then rule, then a fingerprint of the normalised code on the line (sequence and identification columns excluded), then a count. A known finding therefore stays known when lines move. `check`, CI and the pre-commit hook fail only on findings that are not in the baseline. The summary still counts baselined findings, and `--no-baseline` lists them.

When you fix existing findings, run `python3 scripts/cobol_lint.py baseline` to remove the entries that no longer occur. Never add new findings to the baseline to get a change through. Fix them, or change the rule's configuration and give a reason.

## Pre-commit hook

Enable the hook once per clone:

```sh
git config core.hooksPath .githooks
```

`.githooks/pre-commit` runs `cobol_lint.py check --staged`. It lints the staged sources, runs the cobc check on staged programs and on any program that copies a staged copybook, and applies the `large-file` limit to every staged file. The hook reads the working-tree copy of each staged file, so stage whole files. If cobc is not installed, the hook prints a warning and skips the syntax check.

## Formatter (`fix`)

`fix` makes only the mechanical changes that cannot alter what the compiler sees:

- converts CRLF to LF;
- expands tabs to spaces using `tab_width` (4). It leaves a tab inside a literal alone (that tab is data), and it leaves a line alone if expansion would push code past column 72. Both cases are reported for manual review;
- removes trailing whitespace, except padding that fills the line to exactly 80 columns (the fixed-record convention);
- adds a missing final newline.

`fix --check` reports what would change without writing anything. Run `fix` only on files you are changing anyway, and do not reformat whole directories in an unrelated change.

## cobc syntax check

For each program in `app/cbl`, `app/app-*/cbl`, `tools/shims` and `tools/drivers`, the check runs `cobc -fsyntax-only -std=ibm -ftab-width=4 -I <own module copybooks> -I <base copybooks>`. GnuCOBOL has no CICS, DB2 or IMS precompiler, so programs that contain `EXEC CICS`, `EXEC SQL` or `EXEC DLI` are listed as skipped, with the reason. They are never counted as passed. GnuCOBOL errors are reported as `cobc-syntax` and warnings as `cobc-warning`. A copybook problem is reported once, against the copybook. The tab width is 4 because `app/cpy/CUSTREC.cpy` uses 4-column tabs, so CBSTM03A only compiles with `-ftab-width=4`.

## Rules

Severity and thresholds are as configured in `cobol-lint.json`.

### Physical layout (format)

| Rule | Severity | Checks |
|:--|:--|:--|
| `crlf-line-ending` | error | CRLF line endings. Files must use LF, because CRLF breaks the upload and compile on the mainframe. Reported once per file. |
| `tab-character` | error | Tabs in fixed-format sources. Column positions then depend on the tab width. |
| `trailing-whitespace` | warning | Trailing blanks, except padding to exactly 80 columns. |
| `line-too-long` | error | Lines longer than the 80-byte record. |
| `text-past-column-72` | warning | Columns 73-80 hold something other than a 1-8 character sequence or change tag, such as text running off the code area or a shifted sequence number. The compiler ignores these columns. Comment lines are skipped. |
| `invalid-indicator` | error | Column 7 is not blank or one of `* - / D d`. |
| `sequence-area-text` | warning | Columns 1-6 are not blank and not a sequence number. Usually a sign of shifted code. |
| `continuation-area-a` | error | A continuation line has text in columns 8-11. |
| `missing-final-newline` | warning | The last line has no newline. GnuCOBOL warns about this too. |

### Structure and dependencies

| Rule | Severity | Checks |
|:--|:--|:--|
| `unresolved-copy` | error | A `COPY` or `EXEC SQL INCLUDE` member that is neither in the repository nor in `external_copybooks` (CICS `DFH*`, MQ `CMQ*`, `SQLCA`/`SQLDA`). |
| `unused-copybook` | warning | A copybook that no program or copybook copies or includes. |
| `unterminated-exec` | error | An `EXEC` block with no `END-EXEC`. |
| `duplicate-paragraph` | error | A paragraph or section name defined twice in one program. |
| `stop-run-in-cics` | error | `STOP RUN` in a CICS program. |
| `batch-uses-cics-copybook` | error | A program without `EXEC CICS` that copies `DFHAID`, `DFHBMSCA` or `DFHEIBLK`, or a BMS symbolic map from a `cpy-bms` directory. |
| `module-boundary` | error | The base application (`app/cbl`, `app/cpy`, ...) copies a copybook or CALLs a program that exists only in an `app/app-*` extension, or one extension depends on another. Extensions may use the base application. These are the boundaries the code follows today. |

### Naming

These rules encode the conventions described in [CONTRIBUTING.md](../CONTRIBUTING.md#naming-conventions).

| Rule | Severity | Checks |
|:--|:--|:--|
| `program-name-format`, `copybook-name-format` | error | Member names are 1-8 characters from `A-Z 0-9 @ # $` and start with a letter or national character. |
| `program-id-mismatch` | error | `PROGRAM-ID` differs from the member (file) name. |
| `program-prefix` | error | A program with `EXEC CICS` does not start with `CO`. |
| `copybook-prefix` | warning | A copybook outside `cpy-bms` does not use a project prefix (`CO CS CV CU CC CI DCL`, `*PCB`, or `IMSFUNCS`). |
| `bms-copybook-name` | error | A `cpy-bms` member has no BMS mapset of the same name in the sibling `bms/` directory. |
| `paragraph-naming` | warning | A paragraph name over 30 characters or containing `_`. Also, in a program where at least 75% of the paragraphs use the `NNNN-NAME` style, a paragraph that does not. |

### Complexity and size

| Rule | Severity | Threshold |
|:--|:--|:--|
| `paragraph-complexity` | warning | Cyclomatic complexity above 20 per paragraph. Complexity is 1 plus the count of `IF`, `WHEN` (other than `WHEN OTHER`), `UNTIL` (covering `PERFORM UNTIL` and `VARYING`), `AND` and `OR`, outside EXEC blocks. |
| `program-complexity` | warning | Complexity summed over all paragraphs above 150. |
| `source-size` | warning | A COBOL or JCL source over 3000 lines or 300000 bytes. |
| `large-file` | error | Any tracked file over 1 MiB. With `--staged`, the check uses the staged size. |

### Dead and duplicate code

| Rule | Severity | Checks |
|:--|:--|:--|
| `unreferenced-paragraph` | warning | A paragraph or section that is not PERFORMed (including `THRU` ranges and whole sections), not a `GO TO` target, not otherwise named (for example in `EXEC CICS HANDLE`), and not reached by falling through from the entry point or a `GO TO` target. |
| `unreachable-code` | warning | A statement that follows an unconditional `GOBACK`, `STOP RUN`, `GO TO`, `EXIT PROGRAM`, `EXEC CICS RETURN`/`XCTL`/`ABEND` or `NEXT SENTENCE` in the same scope. `IF`/`ELSE`, `EVALUATE`/`WHEN`, inline `PERFORM` and `AT END`-style phrases are tracked. Unreachable no-op `EXIT`/`CONTINUE` statements, and a safety-net `GOBACK` after a CICS `RETURN`, are not reported. |
| `duplicate-block` | warning | 12 or more consecutive normalised procedure-division lines (blank lines and comments skipped, whitespace collapsed, upper-cased) that also occur elsewhere, in the same program or another. Generated `cpy-bms` maps are excluded. |

### Tech debt, performance and data handling

| Rule | Severity | Checks |
|:--|:--|:--|
| `tech-debt-marker` | warning | A `TODO`, `FIXME`, `HACK` or `XXX` comment with no issue reference. Write it as `TODO(#123)` or `TODO(PROJ-123)`. `report` prints the marker counts. |
| `n-plus-one` | warning | A per-row database call inside a loop. The loop is a `PERFORM ... UNTIL/VARYING`, inline or out-of-line and followed through every paragraph it PERFORMs. The rule fires when the loop reads rows (COBOL `READ`, `EXEC SQL FETCH`, `EXEC CICS READNEXT/READPREV`, `EXEC DLI GN/GNP/GHN/GHNP`) and also runs `EXEC SQL SELECT/INSERT/UPDATE/DELETE/MERGE` or `EXEC DLI GU/GHU/ISRT/REPL/DLET`. |
| `sensitive-display` | error | A `DISPLAY` that writes a sensitive field to the job log or console. The field patterns in `cobol-lint.json` (`rules.sensitive-display.field_patterns`) cover SSN, government ID, date of birth, customer name, address and phone, EFT account, FICO score, card number, CVV, embossed name, card expiry, and passwords. A group item that contains such a field is sensitive too, so `DISPLAY CARD-RECORD` is reported. |

### JCL

| Rule | Severity | Checks |
|:--|:--|:--|
| `jcl-statement-format` | error | A line outside in-stream data (`DD *`, `DD DATA`, `DLM=`) that does not start with `//` or `/*`. |
| `jcl-name-field` | error | The JCL member name, or a job, step or DD name (each part of `STEP.DD`), is not 1-8 alphanumeric or national characters. |

The format rules above also apply to JCL. In JCL the identification check skips `//*` comments and in-stream data.

### cobc

| Rule | Severity | Checks |
|:--|:--|:--|
| `cobc-syntax` | error | Errors from `cobc -fsyntax-only`. |
| `cobc-warning` | warning | Warnings from `cobc -fsyntax-only`. |

## Changing a rule

Every rule must appear in `cobol-lint.json`; the linter refuses to run if a rule is missing or unknown. To change a threshold or exemption, edit the rule's entry and put the reason in its `reason` field. After any configuration change, run `python3 scripts/cobol_lint.py baseline` and review the baseline diff.
