---
name: fix-lint-finding
description: >-
  Use when make lint, scripts/lint.sh or the pre-commit hook reports a CardDemo lint finding: fix, verify, and keep the lint baseline shrink-only. NOT for changing rule thresholds.
metadata:
  version: '1.0.0'
---

# Fix a lint finding

`scripts/lint.sh` runs `python3 scripts/cobol_lint.py check`: every lint rule plus `cobc -fsyntax-only` for programs without EXEC CICS, SQL or DLI. It fails only on findings that are not in `cobol-lint-baseline.json`. Exit codes: 0 no new findings, 1 new findings, 2 configuration error or cobc missing. Every rule is described in `docs/linting.md`.

## Policy

- Fix the cause. Never add a finding to the baseline to get a change through.
- The baseline may only shrink. Regenerate it only after fixing findings, and check that nothing was added.
- Changing a rule's severity or threshold in `cobol-lint.json` needs a reason in that rule's `reason` field and a baseline review. Prefer fixing the code.

## Steps

1. Run the linter. New findings print as `path:line: severity [rule-id] message`, followed by a table with total, baselined and new counts per rule.

   ```sh
   scripts/lint.sh
   ```

2. Look up the rule:

   ```sh
   python3 scripts/cobol_lint.py rules
   ```

3. Narrow to one file, or one rule in one file:

   ```sh
   python3 scripts/cobol_lint.py check app/cbl/CBACT02C.cbl
   python3 scripts/cobol_lint.py check --rule tab-character app/cbl/CBACT02C.cbl
   ```

4. Fix it.
   - Mechanical rules (`crlf-line-ending`, `tab-character`, `trailing-whitespace`, `missing-final-newline`): preview, then apply the formatter to the file you are changing. It works on the whole file, so it may also fix baselined findings on other lines of that file; that is expected. Do not run it on files you are not otherwise changing.

     ```sh
     python3 scripts/cobol_lint.py fix app/cbl/CBACT02C.cbl --check
     python3 scripts/cobol_lint.py fix app/cbl/CBACT02C.cbl
     ```

   - `sensitive-display`: do not write the field to the job log. Remove the `DISPLAY`, or display a non-sensitive reference key instead (see `docs/security/data-handling.md`).
   - `cobc-syntax`, naming, structure and JCL rules: change the code as the message says. Keep the fixed format: code in columns 8-72, LF line endings, no tabs.

5. Verify. The file check must exit 0, then the full run:

   ```sh
   python3 scripts/cobol_lint.py check app/cbl/CBACT02C.cbl --quiet
   scripts/lint.sh --quiet
   ```

6. If the run ends with `note: N baseline entries no longer occur`, prune them, then confirm the baseline only shrank:

   ```sh
   python3 scripts/cobol_lint.py baseline
   python3 - <<'EOF'
   import json, subprocess
   old = json.loads(subprocess.run(["git", "show", "HEAD:cobol-lint-baseline.json"],
                                   capture_output=True, text=True, check=True).stdout)["findings"]
   new = json.load(open("cobol-lint-baseline.json"))["findings"]
   grown = [(p, r, f) for p, rules in new.items() for r, fps in rules.items()
            for f, n in fps.items() if n > old.get(p, {}).get(r, {}).get(f, 0)]
   print("baseline entries added or grown:", grown or "none")
   EOF
   ```

   It must print `none`. Commit the baseline change with the fix.

7. Run `make lint` (the linter plus its own tests), then `make check`.

## Pre-commit hook

With `git config core.hooksPath .githooks`, every commit runs `cobol_lint.py check --staged` on the staged files. The hook reads the working-tree copy, so stage whole files. Bypassing it with `--no-verify` only moves the failure to CI.
