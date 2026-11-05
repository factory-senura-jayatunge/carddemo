"""Build an edge-case POSTTRAN data directory from the sample data.

Usage: python3 gen_stress.py OUT_DIR
Each change below targets a reject reason or edge case of the CBTRN02C spec.
"""
import sys, shutil
from decimal import Decimal
from pathlib import Path
REPO = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(REPO))
from tests import oracle as o
SRC = REPO / 'app' / 'data' / 'ASCII'
if len(sys.argv) != 2:
    sys.exit(__doc__)
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
for f in SRC.iterdir():
    shutil.copy(f, OUT / f.name)
daily = o.sample_records('dailytran.txt')
xref = o.sample_records('cardxref.txt')
accts = o.sample_records('acctdata.txt')
D = o.DALYTRAN
def setf(rec, **kw):
    return o.set_fields(D, rec.encode('latin-1'), **kw).decode('latin-1')
daily = list(reversed(daily))            # different order -> different running balances
card_of_acct = {Record.raw('XREF-ACCT-ID'): Record.raw('XREF-CARD-NUM') for Record in o.records_of(o.XREF, [x.encode() for x in xref])}
# 100: unknown cards
for i in (3, 77, 150):
    daily[i] = setf(daily[i], **{'DALYTRAN-CARD-NUM': '0000000000000000'})
# 101: card in xref, account missing
orphans = ['4999999999999991', '4999999999999992']
for n, c in enumerate(orphans):
    xref.append(c + '00000009' + str(n) + '9999999999' + str(n))
for i, c in zip((10, 200), orphans):
    daily[i] = setf(daily[i], **{'DALYTRAN-CARD-NUM': c})
# 103 / 102+103: future-dated transactions, one also huge
daily[20] = setf(daily[20], **{'DALYTRAN-ORIG-TS': '2030-01-01 00:00:00.000000'})
daily[21] = setf(daily[21], **{'DALYTRAN-ORIG-TS': '2030-01-01 00:00:00.000000', 'DALYTRAN-AMT': Decimal('999999999.99')})
# huge amount alone -> 102
daily[30] = setf(daily[30], **{'DALYTRAN-AMT': Decimal('999999999.99')})
# zero amount, negative amounts, new type/category combinations
daily[40] = setf(daily[40], **{'DALYTRAN-AMT': Decimal('0.00')})
daily[41] = setf(daily[41], **{'DALYTRAN-AMT': Decimal('-123.45'), 'DALYTRAN-TYPE-CD': '02', 'DALYTRAN-CAT-CD': '0002'})
daily[42] = setf(daily[42], **{'DALYTRAN-TYPE-CD': '01', 'DALYTRAN-CAT-CD': '0005'})
# account expiry edge cases: acct 2 expires the day before the transactions, acct 3 the same day, acct 4 blank
A = o.ACCOUNT
def seta(rec, **kw):
    return o.set_fields(A, rec.encode('latin-1'), **kw).decode('latin-1')
for k, v in enumerate(accts):
    aid = v[:11]
    if aid == '00000000002': accts[k] = seta(v, **{'ACCT-EXPIRAION-DATE': '2022-06-09'})
    if aid == '00000000003': accts[k] = seta(v, **{'ACCT-EXPIRAION-DATE': '2022-06-10'})
    if aid == '00000000004': accts[k] = seta(v, **{'ACCT-EXPIRAION-DATE': '          '})
    if aid == '00000000005': accts[k] = seta(v, **{'ACCT-CREDIT-LIMIT': Decimal('10.00')})   # forces 102 incl. negatives
# extra transactions on account 5 including a refund
c5 = card_of_acct['00000000005']
base = daily[0]
daily.append(setf(base, **{'DALYTRAN-ID': '0000009999999901', 'DALYTRAN-CARD-NUM': c5, 'DALYTRAN-AMT': Decimal('-5.00'), 'DALYTRAN-TYPE-CD': '03'}))
daily.append(setf(base, **{'DALYTRAN-ID': '0000009999999902', 'DALYTRAN-CARD-NUM': c5, 'DALYTRAN-AMT': Decimal('3.00')}))
(OUT / 'dailytran.txt').write_text('\n'.join(daily) + '\n', encoding='latin-1')
(OUT / 'cardxref.txt').write_text('\n'.join(sorted(xref, key=lambda r: r[:16])) + '\n', encoding='latin-1')
(OUT / 'acctdata.txt').write_text('\n'.join(accts) + '\n', encoding='latin-1')
print('daily', len(daily), 'xref', len(xref), 'accts', len(accts))
