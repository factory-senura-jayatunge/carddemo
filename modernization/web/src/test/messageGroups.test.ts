import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { CORE_KEYS, ENTRY_GROUPS, GROUPS, entriesOfGroupFile, groupOf, keysOfGroupFile } from '../../scripts/message-groups.mjs';
import { messages } from '@/messages/all';

const web = resolve(import.meta.dirname, '../..');
const dir = join(web, 'src/messages');
const temp: string[] = [];

/** gen-messages --check on a catalogue of the given entries; it exits before comparing when the catalogue is invalid. */
function generate(entries: Record<string, { source: string }>) {
  const folder = mkdtempSync(join(tmpdir(), 'messages-'));
  temp.push(folder);
  const file = join(folder, 'messages.json');
  const full = Object.fromEntries(Object.entries(entries).map(([key, m]) => [key, { code: key, text: 'Text', severity: 'error', params: [], ...m }]));
  writeFileSync(file, JSON.stringify(full));
  return spawnSync(process.execPath, ['scripts/gen-messages.mjs', '--check'], { cwd: web, env: { ...process.env, CARDDEMO_MESSAGES_JSON: file }, encoding: 'utf8' });
}

afterEach(() => {
  for (const folder of temp.splice(0)) rmSync(folder, { recursive: true, force: true });
});

describe('message catalogue groups', () => {
  it('a spec item goes to the group of its area', () => {
    expect(groupOf('SIGNED_OFF', 'VAL-SGN-001')).toBe('core');
    expect(groupOf('NOTHING_TO_PAY', 'BR-BILL-003')).toBe('bill');
    expect(groupOf('CONFIRM_REQUIRED', 'BR-RPTS-005')).toBe('report');
    expect(groupOf('KEY_REQUIRED', 'VAL-TRNA-001')).toBe('transaction');
    expect(groupOf('TT_TYPE_REQUIRED', 'VAL-TTUP-001')).toBe('reference');
  });

  it('a decision goes to its named group, and the key prefix decides for any other source', () => {
    expect(groupOf('NEW_PASSWORD_MISMATCH', 'D-SEC-07')).toBe('core');
    expect(groupOf('REPORT_RUN_STALLED', 'D-48')).toBe('report');
    expect(groupOf('TT_NOT_FOUND', 'target')).toBe('reference');
    expect(groupOf('REPORT_NOT_READY', 'target')).toBe('report');
    expect(groupOf('INVALID_KEY', 'CSMSG01Y')).toBe('core');
    expect(groupOf('USER_ID_MISMATCH', 'target')).toBe('user');
    expect(groupOf('ACCOUNT_UPDATE_BATCH_WINDOW', 'target')).toBe('account');
    expect(groupOf('AUSM_INVALID_PAGE_SIZE', 'target')).toBe('authorization');
    expect(groupOf('INVALID_RUN_ID', 'target')).toBe('report');
  });

  it('a spec item followed by text still goes to the group of its area, before any prefix', () => {
    expect(groupOf('TRAN_TYPE_IN_USE', 'BR-TTUP-002 (delete)')).toBe('reference');
    expect(groupOf('TT_NO_RECORDS', 'FLOW-TTLS-018')).toBe('reference');
    expect(groupOf('TT_HAS_CATEGORIES', 'DEF-TTLS-002')).toBe('reference');
    expect(() => groupOf('NEW_KEY', 'VAL-XXXX-001 (new)')).toThrow('NEW_KEY: area XXXX (VAL-XXXX-001 (new)) has no message group');
  });

  it('a key with no spec item, decision or prefix goes to core only when CORE_KEYS lists it', () => {
    for (const key of CORE_KEYS) expect(groupOf(key, 'target')).toBe('core');
    expect(() => groupOf('TYPE_DESC_REQUIRED', 'target')).toThrow(
      'TYPE_DESC_REQUIRED: source target is not a spec item or a decision, and the key has no group prefix and is not in CORE_KEYS',
    );
    expect(() => groupOf('NEW_NOTICE', 'CSMSG01Y')).toThrow('NEW_NOTICE: source CSMSG01Y');
    expect(() => groupOf('NEW_NOTICE', 'VAL-TTUP-001a')).toThrow('NEW_NOTICE: source VAL-TTUP-001a');
  });

  it('every CORE_KEYS key is in the generated core table', () => {
    const core = keysOfGroupFile(readFileSync(join(dir, 'core.ts'), 'utf8'));
    expect(CORE_KEYS.filter((key) => !core.includes(key))).toEqual([]);
  });

  it('an area or a decision with no group fails, naming the key', () => {
    expect(() => groupOf('NEW_KEY', 'VAL-XXXX-001')).toThrow('NEW_KEY: area XXXX (VAL-XXXX-001) has no message group');
    expect(() => groupOf('NEW_KEY', 'D-999')).toThrow('NEW_KEY: decision D-999 has no message group');
  });

  it('the generator refuses a catalogue with an unplaced area (exit 2), naming the key', () => {
    const refused = generate({ SIGNED_OFF: { source: 'VAL-SGN-001' }, NEW_KEY: { source: 'VAL-XXXX-001' } });
    expect(refused.status).toBe(2);
    expect(refused.stderr).toContain('NEW_KEY: area XXXX');
    const unplaced = generate({ SIGNED_OFF: { source: 'VAL-SGN-001' }, TYPE_DESC_REQUIRED: { source: 'target' } });
    expect(unplaced.status).toBe(2);
    expect(unplaced.stderr).toContain('TYPE_DESC_REQUIRED: source target is not a spec item or a decision');
  });

  it('the generator places reference keys in their own file (exit 1: valid, but the files differ)', () => {
    const placed = generate({
      SIGNED_OFF: { source: 'VAL-SGN-001' },
      TT_TYPE_CODE_REQUIRED: { source: 'VAL-TTUP-001' },
      TT_DELETED: { source: 'ERR-TTLS-002' },
      TT_BATCH_WINDOW: { source: 'target' },
      TRAN_TYPE_IN_USE: { source: 'BR-TTUP-002 (delete)' },
    });
    expect(placed.status).toBe(1);
    expect(placed.stderr).toContain('src/messages/reference.ts differs from the catalogue');
  });

  it('reads the keys and texts of a group file, escapes included', () => {
    const text = "  A_KEY: { code: 'A', text: 'It\\'s {name}.', severity: 'error', params: ['name'] },\n  B_KEY: { code: 'B', text: 'Plain', severity: 'info', params: [] },\n";
    expect(entriesOfGroupFile(text)).toEqual([
      { key: 'A_KEY', text: "It\\'s {name}." },
      { key: 'B_KEY', text: 'Plain' },
    ]);
  });

  it('the generated files hold every key once, each entry group is a group, and only the entry groups are named as such', () => {
    expect(ENTRY_GROUPS.every((group) => GROUPS.includes(group))).toBe(true);
    const files = readdirSync(dir).filter((name) => !['index.ts', 'all.ts'].includes(name));
    expect(files.every((name) => GROUPS.includes(name.replace(/\.ts$/, '')))).toBe(true);
    const keys = files.flatMap((name) => keysOfGroupFile(readFileSync(join(dir, name), 'utf8')));
    expect(new Set(keys).size).toBe(keys.length);
    expect([...keys].sort()).toEqual(Object.keys(messages).sort());
    expect(readFileSync(join(dir, 'index.ts'), 'utf8')).not.toMatch(/^import \{/m);
  });
});
