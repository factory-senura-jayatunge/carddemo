"""Live independent load checks inside infra's locked, private Session."""
from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import tempfile

from . import digest, failures, hd, lookups, source, metadata
from modernization.verify.spec_jobs.common import Finding, pii_scan, require_integer, equal_scalar
from modernization.verify.spec_jobs.layouts import ROOT
from modernization.verify.spec_jobs.session import Execution, Session, LOGINS

HD_FILE = ROOT / "app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat"
FILE_STEPS = metadata.FILE_STEPS
CUSTOMER_MASKS = ("ssn", "govt_issued_id", "dob", "eft_account_id")


def file_types(name: str) -> dict[str, digest.Column]:
    table = source.TABLES[name]
    fixed = {"acct_id", "cust_id", "ssn", "cat_cd", "type_cd", "card_num", "group_id",
             "active_status", "state_cd", "country_cd", "eft_account_id", "pri_card_holder_ind"}
    result = {}
    for column, field_name in table.columns.items():
        field = table.layout[field_name]
        if column in source.DATE_COLUMNS or (name == "core.account" and column == "expiration_date"):
            kind = digest.Column("date")
        elif field.signed:
            kind = digest.Column("decimal", scale=field.scale)
        elif column == "fico_score":
            kind = digest.Column("smallint")
        else:
            kind = digest.Column("nchar" if column in fixed or (name == "core.card" and column == "expiration_date")
                                 else "nvarchar", field.length)
        result[column] = kind
    if table.kept_filler is not None:
        result["legacy_filler"] = digest.Column("nchar", table.layout["__LENGTH__"].length - table.kept_filler)
    if name == "core.account":
        result.update(legacy_zip_group=digest.Column("nchar", 20), zip_group_origin=digest.Column("char", 1))
    return result


def auth_types() -> tuple[dict, dict]:
    summary = dict(acct_id=digest.Column("char", 11), cust_id=digest.Column("char", 9))
    summary.update({c: digest.Column("decimal", scale=2) for c in hd.SUMMARY_AMOUNT})
    summary.update({c: digest.Column("int") for c in ("approved_auth_cnt", "declined_auth_cnt")})
    detail = {c: digest.Column("nvarchar" if c in hd.VARIABLE_TEXT else "nchar", span[1])
              for c, span in hd.DETAIL_TEXT.items()}
    detail.update(acct_id=digest.Column("char", 11), auth_date_9c=digest.Column("int"),
                  auth_time_9c=digest.Column("int"), processed_at=digest.Column("datetime2", scale=3),
                  transaction_amt=digest.Column("decimal", scale=2), approved_amt=digest.Column("decimal", scale=2),
                  fraud_rpt_date=digest.Column("date"), fraud_changed_by=digest.Column("nchar", 8),
                  matched_tran_pk=digest.Column("bigint"), matched_tran_id=digest.Column("nchar", 16),
                  matched_cycle_id=digest.Column("int"), matched_at=digest.Column("datetime2", scale=3))
    return summary, detail


def projected(session: Session, table: str, columns: tuple[str, ...], order: tuple[str, ...],
              types: dict) -> list[dict]:
    # Fixed source-defined identifiers only. Decimal casts avoid JSON float loss.
    expressions = [(f"CONVERT(varchar(80),[{c}]) [{c}]" if types[c].kind == "decimal" else f"[{c}]")
                   for c in columns]
    rows = session.query("SELECT " + ",".join(expressions) + " FROM " + table +
                         " ORDER BY " + ",".join(f"[{c}]" for c in order))
    for row in rows:
        for column in columns:
            if types[column].kind in ("tinyint", "smallint", "int", "bigint") and row[column] is not None:
                require_integer(row[column])
    return rows


def unmask_reader(session: Session, login: str, table: str, columns: tuple[str, ...]) -> Session:
    reader = session.as_login(login)
    grants = reader.query(
        "SELECT c.name column_name FROM sys.database_permissions p JOIN sys.columns c "
        "ON p.major_id=c.object_id AND p.minor_id=c.column_id "
        f"WHERE p.major_id=OBJECT_ID('{table}') AND p.class=1 AND p.minor_id>0 "
        "AND p.grantee_principal_id=USER_ID() AND p.permission_name='UNMASK' AND p.state IN ('G','W')")
    if not set(columns) <= {row["column_name"] for row in grants}:
        raise PermissionError("EXISTING_COLUMN_UNMASK_REQUIRED")
    return reader


def compare_aggregate(session: Session, name: str, rows: list[dict], types: dict,
                      keys: tuple[str, ...], case: str) -> list[Finding]:
    columns = tuple(types)
    wanted = digest.aggregate(rows, types=types, columns=columns, order_by=keys)
    actual = session.snapshot_digest(name, columns=columns, order_by=keys, aggregate=True)
    return [] if wanted == actual and equal_scalar(actual.get("rows"), wanted["rows"]) else [
        Finding(case, "SOURCE_AGGREGATE", name)]


def validate(session: Session, *, mode: str, source_dir: Path, authorization_file: Path = HD_FILE,
             manifest: Path | None = None) -> list[Finding]:
    findings = []
    fixture = session.as_login("fixture")
    for name, table in source.TABLES.items():
        raw = (source_dir / table.filename).read_bytes()
        declaration = manifest.read_bytes() if manifest and mode == "fixed" and name == "core.account" else None
        wanted = source.parse(name, raw, mode=mode, manifest=declaration)
        if len(wanted) != table.count:
            findings.append(Finding(mode, "SAMPLE_COUNT", table.filename))
        types = file_types(name)
        findings += compare_aggregate(session, name, wanted, types, table.keys, mode)
        reader = fixture
        if name == "core.customer":
            reader = unmask_reader(session, "account", name, CUSTOMER_MASKS)
        columns = tuple(c for c in types if not (name in ("core.card", "core.card_xref") and c == "card_num"))
        actual = projected(reader, name, columns, table.keys, types)
        if name in ("core.card", "core.card_xref"):
            # Aggregate above validates the real key set and association. These
            # source keys permit line-local re-encoding without reading masked
            # keys; they are never used as evidence of the database's key set.
            for row, expected in zip(actual, wanted):
                row["card_num"] = expected["card_num"]
        findings += source.validate(name, raw, actual, mode=mode, manifest=declaration)
    lookup_rows = lookups.parse((ROOT / "app/cpy/CSLKPCDY.cpy").read_text())
    for name, wanted in lookup_rows.items():
        columns = tuple(wanted[0])
        types = {c: digest.Column("char", 3 if c == "area_code" else 2) for c in columns}
        actual = projected(fixture, name, columns, columns, types)
        if sorted(tuple(r[c] for c in columns) for r in actual) != sorted(tuple(r[c] for c in columns) for r in wanted):
            findings.append(Finding(mode, "LOOKUP_ROWS", name))
    raw = authorization_file.read_bytes()
    wanted_auth = hd.parse(raw)
    types_auth = auth_types()
    actual_auth = []
    for name, wanted, types, keys in zip(
        ("pauth.auth_summary", "pauth.auth_detail"), wanted_auth, types_auth,
        (("acct_id",), ("acct_id", "auth_date_9c", "auth_time_9c"))):
        findings += compare_aggregate(session, name, wanted, types, keys, mode)
        reader = unmask_reader(session, "authorization", name, ("card_num",)) if name.endswith("auth_detail") else fixture
        if name.endswith("auth_detail"):
            actual = projected(reader, name, (*types, "auth_id"), ("auth_id",),
                               {**types, "auth_id": digest.Column("bigint")})
            if [tuple(r[c] for c in keys) for r in actual] != [tuple(r[c] for c in keys) for r in wanted]:
                findings.append(Finding(mode, "AUTH_ID_FILE_ORDER", name))
            actual_auth.append(actual)
        else:
            actual_auth.append(projected(reader, name, tuple(types), keys, types))
    findings += hd.validate(raw, *actual_auth)
    checks = fixture.query(
        "SELECT (SELECT COUNT_BIG(*) FROM core.[transaction]) transactions,"
        "(SELECT COUNT_BIG(*) FROM pauth.auth_detail d LEFT JOIN pauth.auth_summary s "
        "ON s.acct_id=d.acct_id WHERE s.acct_id IS NULL) orphan_details")
    if len(checks) != 1 or not equal_scalar(checks[0]["transactions"], 0) or not equal_scalar(checks[0]["orphan_details"], 0):
        findings.append(Finding(mode, "LOAD_CROSS_TABLE", "core.transaction/pauth"))
    sequence = fixture.query(
        "SELECT CONVERT(varchar(32),CASE WHEN last_used_value IS NULL THEN CONVERT(bigint,start_value) "
        "ELSE CONVERT(bigint,current_value)+CONVERT(bigint,increment) END) next_value "
        "FROM sys.sequences WHERE object_id=OBJECT_ID('core.tran_id_seq')")
    if len(sequence) != 1 or str(sequence[0]["next_value"]) != "9000000000000000":
        findings.append(Finding(mode, "SEQUENCE_NEXT_VALUE", "core.tran_id_seq"))
    mask = fixture.query(
        "SELECT is_masked FROM sys.masked_columns WHERE object_id=OBJECT_ID('core.card') AND name='cvv_cd'")
    if len(mask) != 1 or not mask[0]["is_masked"]:
        findings.append(Finding(mode, "CVV_MASK_REQUIRED", "core.card", column="cvv_cd"))
    for login in LOGINS:
        probe = session.as_login(login).query(
            "SELECT HAS_PERMS_BY_NAME('core.card','OBJECT','UNMASK','cvv_cd','COLUMN') can_unmask")
        if len(probe) != 1 or not equal_scalar(probe[0]["can_unmask"], 0):
            findings.append(Finding(mode, "CVV_UNMASK_FORBIDDEN", login, column="cvv_cd"))
    return findings


def failure_snapshot(session: Session, table: str) -> dict:
    if table in source.TABLES:
        types = file_types(table)
        keys = source.TABLES[table].keys
        if table != "core.card_xref":
            types["row_version"] = digest.Column("rowversion")
        if table == "core.card":
            types["card_id"] = digest.Column("bigint")
    else:
        types = auth_types()[0 if table.endswith("auth_summary") else 1]
        types["row_version"] = digest.Column("rowversion")
        keys = ("acct_id",)
        if table.endswith("auth_detail"):
            types["auth_id"] = digest.Column("bigint")
            keys = ("auth_id",)
    result = session.snapshot_digest(table, columns=tuple(types), order_by=keys, aggregate=True)
    require_integer(result["rows"])
    return result


def failure(session: Session, case: str, mode: str, source_dir: Path, work: Path,
            authorization_file: Path) -> tuple[list[Finding], Execution]:
    table = "core.card_xref" if case == "missing_account" else "core.account"
    if case == "blank_response":
        target = work / "blank-response.dat"
        target.write_bytes(failures.blank_response(authorization_file.read_bytes()))
        target.chmod(0o600)
        before = {t: failure_snapshot(session, t) for t in ("pauth.auth_summary", "pauth.auth_detail")}
        execution = session.load(source_dir, authorization_file=target)
        after = {t: failure_snapshot(session, t) for t in before}
    else:
        target = work / "source"
        manifest = failures.prepare(source_dir, target, case, table)
        before = failure_snapshot(session, table)
        execution = session.load(target, manifest=manifest, authorization_file=authorization_file)
        after = failure_snapshot(session, table)
        if not failures.rejected(case, mode):
            problems = validate(session, mode=mode, source_dir=target,
                                authorization_file=authorization_file, manifest=manifest)
            problems += metadata.validate(execution, mode=mode, source_dir=target,
                                          authorization_file=authorization_file, manifest=manifest)
            if execution.status != "COMPLETED":
                problems.append(Finding(case, "LEGACY_MANIFEST_IGNORED", table))
            return problems, execution
    findings = []
    if execution.status != "FAILED":
        findings.append(Finding(case, "LOAD_REFUSED", table))
    step = ("loadPendingAuthorizations" if case == "blank_response" else FILE_STEPS[table])
    if execution.step_statuses.get(step) != "FAILED":
        findings.append(Finding(case, "EXPECTED_STEP_FAILED", step))
    if before != after:
        findings.append(Finding(case, "FAILED_STEP_UNCHANGED", table))
    if case == "manifest_bytes" and mode == "fixed":
        findings += metadata.validate_account_failure(execution, (target / "acctdata.txt").read_bytes(), manifest)
    return findings, execution


def run(session: Session, *, source_dir: Path | None = None, authorization_file: Path = HD_FILE,
        out=print) -> int:
    source_dir = source_dir or ROOT / "app/data/ASCII"
    failed = []
    # Read source sentinels in memory only; no report or exception includes them.
    customer = source.parse("core.customer", (source_dir / "custdata.txt").read_bytes())[0]
    sentinels = tuple(str(customer[c]) for c in ("first_name", "ssn", "govt_issued_id", "dob", "eft_account_id")
                      if customer[c]) + (source.parse("core.card", (source_dir / "carddata.txt").read_bytes())[0]["card_num"],)
    for mode in ("legacy", "fixed"):
        for case in ("sample", "out_of_order", "duplicate", "missing_account", "manifest_bytes", "blank_response"):
            findings, execution = [], Execution("NOT_RUN")
            try:
                session.reset(mode=mode)
                if case == "sample":
                    execution = session.load(source_dir, authorization_file=authorization_file)
                    findings = validate(session, mode=mode, source_dir=source_dir, authorization_file=authorization_file)
                    findings += metadata.validate(execution, mode=mode, source_dir=source_dir,
                                                  authorization_file=authorization_file)
                else:
                    with tempfile.TemporaryDirectory(prefix="oracle-", dir=session.var_dir) as tmp:
                        findings, execution = failure(session, case, mode, source_dir, Path(tmp), authorization_file)
            except Exception:
                findings.append(Finding(case, "CASE_EXECUTION_FAILED", "dataLoadJob"))
            try:
                findings += pii_scan(case, {"validator.report": "\n".join(map(str, findings)),
                                            "execution.exit_description": execution.exit_description,
                                            "server.log": session.log_bytes()}, sentinels)
            except Exception:
                findings.append(Finding(case, "PII_SCAN_FAILED", "dataLoadJob"))
            out(f"{'FAIL' if findings else 'ok'} data-oracle {mode} {case}")
            for finding in findings:
                out(str(finding))
            failed.extend(findings)
    out("verify-data-oracle: " + ("FAILED" if failed else "OK"))
    return 1 if failed else 0
