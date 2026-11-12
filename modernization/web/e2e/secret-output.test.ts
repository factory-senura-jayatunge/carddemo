import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Playwright's JSON reports, which CI uploads, carry a failing matcher's expected and received
 * values in the error message, plus the test's stdout, stderr and attachments. A value assertion
 * on a password field, a secret in a matcher argument or a URL, console output or an attachment
 * would publish a filled value on the first failure. This scans the Playwright sources under e2e/
 * for those patterns; it checks source text, so it cannot see a value that reaches a matcher
 * through a function parameter.
 */

const SECRET = /password|passwd|secret|token|credential/i;
// Locator state matchers: their failure message names the locator and the state, never a value.
const STATE_MATCHERS = new Set([
  'toBeAttached',
  'toBeChecked',
  'toBeDisabled',
  'toBeEditable',
  'toBeEnabled',
  'toBeFocused',
  'toBeHidden',
  'toBeInViewport',
  'toBeVisible',
  'toHaveAccessibleDescription',
  'toHaveAccessibleName',
  'toHaveCount',
  'toHaveRole',
]);
const SECRET_LOCATOR = /\b(?:getBy\w+|locator)\(\s*(['"`])(?:(?!\1).)*(?:password|passwd|secret|token|credential)/is;
const PASSWORD_INPUT = /type\s*=\s*['"]?password/i;
const SECRET_QUERY = /[?&;#](?:\w*(?:password|passwd|secret|token|credential)\w*)=/i;

/** The source with comments blanked (`code`) and also with literal contents blanked (`bare`), offsets kept. */
export function mask(src: string): { code: string; bare: string; literals: string[] } {
  const code = src.split('');
  const bare = src.split('');
  const literals: string[] = [];
  const blank = (from: number, to: number, both: boolean) => {
    for (let k = from; k < to; k++) {
      if (src[k] === '\n') continue;
      bare[k] = ' ';
      if (both) code[k] = ' ';
    }
  };
  const significant = () => {
    for (let k = i - 1; k >= 0; k--) if (!/\s/.test(bare[k] ?? '')) return { ch: bare[k] ?? '', at: k };
    return { ch: '', at: -1 };
  };
  const braces: ('code' | 'template')[] = [];
  let i = 0;
  const template = () => {
    // i is just past a backtick or a closing brace of ${}; read to the next ` or ${.
    const start = i;
    while (i < src.length && src[i] !== '`' && !(src[i] === '$' && src[i + 1] === '{')) i += src[i] === '\\' ? 2 : 1;
    literals.push(src.slice(start, i));
    blank(start, i, false);
    if (src[i] === '`') i++;
    else {
      braces.push('template');
      i += 2;
    }
  };
  while (i < src.length) {
    const c = src[i];
    if (c === '/' && src[i + 1] === '/') {
      const end = src.indexOf('\n', i);
      blank(i, end < 0 ? src.length : end, true);
      i = end < 0 ? src.length : end;
    } else if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      blank(i, end < 0 ? src.length : end + 2, true);
      i = end < 0 ? src.length : end + 2;
    } else if (c === "'" || c === '"') {
      let j = i + 1;
      while (j < src.length && src[j] !== c && src[j] !== '\n') j += src[j] === '\\' ? 2 : 1;
      literals.push(src.slice(i + 1, j));
      blank(i + 1, j, false);
      i = j + 1;
    } else if (c === '`') {
      i++;
      template();
    } else if (c === '{') {
      braces.push('code');
      i++;
    } else if (c === '}') {
      i++;
      if (braces.pop() === 'template') template();
    } else if (c === '/') {
      const prev = significant();
      const word = /[\w$]+$/.exec(bare.slice(0, prev.at + 1).join(''))?.[0];
      const regexStart = prev.at < 0 || '(,=:[!&|?{};+-*%<>~^'.includes(prev.ch) || ['return', 'typeof', 'case'].includes(word ?? '');
      if (!regexStart) {
        i++;
        continue;
      }
      let j = i + 1;
      let inClass = false;
      while (j < src.length && src[j] !== '\n' && (inClass || src[j] !== '/')) {
        if (src[j] === '\\') j++;
        else if (src[j] === '[') inClass = true;
        else if (src[j] === ']') inClass = false;
        j++;
      }
      literals.push(src.slice(i + 1, j));
      blank(i + 1, j, false);
      i = j + 1;
    } else i++;
  }
  return { code: code.join(''), bare: bare.join(''), literals };
}

/** Index of the bracket that closes the one at `open`, in literal-free text. */
function closing(bare: string, open: number): number {
  const pairs: Record<string, string> = { '(': ')', '[': ']', '{': '}' };
  const stack: string[] = [];
  for (let k = open; k < bare.length; k++) {
    const ch = bare.charAt(k);
    const pair = pairs[ch];
    if (pair) stack.push(pair);
    else if (ch === stack[stack.length - 1]) {
      stack.pop();
      if (stack.length === 0) return k;
    }
  }
  return bare.length;
}

interface Source {
  file: string;
  code: string;
  bare: string;
  literals: string[];
}

type Decls = Map<string, { code: string; bare: string }[]>;

function declarations(sources: Source[]): Decls {
  const decls: Decls = new Map();
  const add = (name: string, s: Source, from: number, to: number) => {
    const list = decls.get(name) ?? [];
    list.push({ code: s.code.slice(from, to), bare: s.bare.slice(from, to) });
    decls.set(name, list);
  };
  for (const s of sources) {
    for (const m of s.bare.matchAll(/\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(?::[^=;]+)?=(?!=)/g)) {
      let k = m.index + m[0].length;
      for (; k < s.bare.length; k++) {
        const ch = s.bare.charAt(k);
        if ('([{'.includes(ch)) k = closing(s.bare, k);
        else if (ch === ';') break;
      }
      add(m[1] ?? '', s, m.index + m[0].length, k);
    }
    for (const m of s.bare.matchAll(/\bfunction\s+([A-Za-z_$][\w$]*)\s*\(/g)) {
      const params = closing(s.bare, m.index + m[0].length - 1);
      const body = s.bare.indexOf('{', params);
      add(m[1] ?? '', s, m.index, body < 0 ? params + 1 : closing(s.bare, body) + 1);
    }
  }
  return decls;
}

/** Identifiers used as values: not a property after a dot, not an object key. */
function valueNames(bare: string): string[] {
  return [...bare.matchAll(/(?<![\w$.])\s*([A-Za-z_$][\w$]*)(?![\w$])(?!\s*:(?!:))/g)].map((m) => m[1] ?? '');
}

const ALIAS = /^\s*(?:await\s+)?[A-Za-z_$][\w$.]*\s*$/;

/**
 * Whether the text names a secret value or, with `locators`, a password field. A name like
 * `password` counts where it is used and through plain aliases (`const pw = PASSWORD`); through
 * any other declaration only a password locator counts, so that a response to a request that
 * sent the password is not taken for the password.
 */
function secretIn(
  text: { code: string; bare: string },
  decls: Decls,
  locators: boolean,
  names = true,
  depth = 0,
  seen = new Set<string>(),
): boolean {
  if (locators && (SECRET_LOCATOR.test(text.code) || PASSWORD_INPUT.test(text.code))) return true;
  if (depth > 4) return false;
  for (const name of valueNames(text.bare)) {
    if (names && SECRET.test(name)) return true;
    if (seen.has(name)) continue;
    seen.add(name);
    for (const decl of decls.get(name) ?? []) {
      const alias = ALIAS.test(decl.bare);
      if ((alias || locators) && secretIn(decl, decls, locators, names && alias, depth + 1, seen)) return true;
    }
  }
  return false;
}

export interface Finding {
  file: string;
  line: number;
  rule: string;
  text: string;
}

export function scan(files: { file: string; text: string }[]): Finding[] {
  const sources: Source[] = files.map(({ file, text }) => ({ file, ...mask(text) }));
  const decls = declarations(sources);
  const findings: Finding[] = [];
  for (const s of sources) {
    const at = (index: number, rule: string, end = index + 40) =>
      findings.push({
        file: s.file,
        line: s.code.slice(0, index).split('\n').length,
        rule,
        text: s.code.slice(index, Math.min(end, index + 120)).replace(/\s+/g, ' ').trim(),
      });
    const slice = (from: number, to: number) => ({ code: s.code.slice(from, to), bare: s.bare.slice(from, to) });

    for (const m of s.bare.matchAll(/\bexpect(?:\s*\.\s*(?:soft|poll))?\s*\(/g)) {
      const open = m.index + m[0].length - 1;
      const close = closing(s.bare, open);
      let k = close + 1;
      const rest = () => s.bare.slice(k);
      let mod: RegExpExecArray | null;
      while ((mod = /^\s*\.\s*(?:not|resolves|rejects)\b/.exec(rest()))) k += mod[0].length;
      const matcher = /^\s*\.\s*([A-Za-z_$][\w$]*)\s*\(/.exec(rest());
      if (!matcher) continue;
      const argsOpen = k + matcher[0].length - 1;
      const argsClose = closing(s.bare, argsOpen);
      const name = matcher[1] ?? '';
      if (!STATE_MATCHERS.has(name) && secretIn(slice(open + 1, close), decls, true))
        at(m.index, `${name} on a password field or secret prints its value`, argsClose + 1);
      if (secretIn(slice(argsOpen + 1, argsClose), decls, false))
        at(m.index, `a secret in the arguments of ${name} is printed as the expected value`, argsClose + 1);
    }
    for (const m of s.bare.matchAll(/\b(?:expect[A-Z][\w$]*|goto|waitForURL)\s*\(/g)) {
      const open = m.index + m[0].length - 1;
      if (secretIn(slice(open + 1, closing(s.bare, open)), decls, false)) at(m.index, 'a secret in a URL or an assertion helper');
    }
    s.literals.forEach((literal) => {
      if (SECRET_QUERY.test(literal)) at(s.code.indexOf(literal), 'a URL query that carries a secret');
    });
    for (const m of s.bare.matchAll(/\bconsole\s*\.\s*\w+\s*\(|\bprocess\s*\.\s*(?:stdout|stderr)\b/g))
      at(m.index, 'console output lands in the report');
    for (const m of s.bare.matchAll(/\.\s*attach\s*\(|\battachments\s*\.\s*push\b/g)) at(m.index, 'an attachment lands in the report');
  }
  return findings;
}

function playwrightSources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return playwrightSources(path);
    return /\.ts$/.test(entry.name) && !/\.test\.ts$/.test(entry.name) ? [path] : [];
  });
}

describe('e2e sources keep filled values out of Playwright reports', () => {
  it('the Playwright sources under e2e/ have no finding', () => {
    const dir = import.meta.dirname;
    const files = playwrightSources(dir).map((path) => ({ file: relative(dir, path), text: readFileSync(path, 'utf8') }));
    expect(files.map((f) => f.file)).toEqual(expect.arrayContaining(['bill.spec.ts', 'fixtures.ts', 'sgn.spec.ts']));
    expect(scan(files)).toEqual([]);
  });

  it("keeps Playwright's own output out of the folders CI uploads", () => {
    const web = join(import.meta.dirname, '..');
    const uploaded = /^(?:\.\/)?(?:test-results|playwright-report)(?:\/|$)/;
    const outputDir = /\boutputDir:\s*'([^']+)'/.exec(readFileSync(join(web, 'playwright.config.ts'), 'utf8'))?.[1];
    expect(outputDir).toBeDefined();
    expect(outputDir).not.toMatch(uploaded);
    const shots = /CARDDEMO_SCREENSHOT_DIR \?\? join\(import\.meta\.dirname, '\.\.', '([^']+)'/.exec(
      readFileSync(join(web, 'e2e', 'screenshots.spec.ts'), 'utf8'),
    )?.[1];
    expect(shots).toBeDefined();
    expect(shots).not.toMatch(uploaded);
  });

  const one = (text: string) => scan([{ file: 'x.spec.ts', text }]).map((f) => f.rule);
  const fixtures = {
    file: 'fixtures.ts',
    text: "export const PASSWORD = seedPassword();\nexport const userId = (page: Page) => page.getByLabel('User ID');\n",
  };
  const withFixtures = (text: string) => scan([fixtures, { file: 'x.spec.ts', text }]).map((f) => f.rule);

  it('allows state matchers on a password field and value matchers on other fields', () => {
    expect(
      withFixtures(`
        const password = (page: Page) => page.getByLabel('Password', { exact: true });
        await expect(password(page)).toBeFocused();
        await expect(password(page)).not.toBeVisible();
        await expect(userId(page)).toHaveValue('USER0001');
        await expect(page).toHaveURL(/\\/sign-on\\/change-password$/);
        await expectMessage(page, 'New Password must differ from the current one ...', 'error');
        const answer = await callApi(page, 'POST', '/api/auth/password', { body: { currentPassword: PASSWORD } });
        expect(answer.status).toBe(400);
        // console.log(PASSWORD) in a comment is fine
        const note = 'console.log in a string is fine';
      `),
    ).toEqual([]);
  });

  it('refuses a value matcher on a password field, however it is reached', () => {
    expect(one("await expect(page.getByLabel('Password')).toHaveValue('');")).toHaveLength(1);
    expect(one("const repeat = page.getByLabel('Repeat new password');\nawait expect(repeat).toHaveValue('x');")).toHaveLength(1);
    expect(one("const pw = () => page.locator('input[type=password]');\nexpect(await pw().inputValue()).toBe('x');")).toHaveLength(1);
    expect(one("await expect.soft(page.getByPlaceholder('API token')).toHaveText('x');")).toHaveLength(1);
    expect(one("await expect.poll(() => page.getByLabel('Password').inputValue()).toBe('');")).toHaveLength(1);
  });

  it('refuses a secret as an expected value, in a URL or in an assertion helper', () => {
    expect(withFixtures('await expect(userId(page)).toHaveValue(PASSWORD);')).toHaveLength(1);
    expect(withFixtures('const pw = PASSWORD;\nawait expect(userId(page)).toHaveValue(pw);')).toHaveLength(1);
    expect(withFixtures('await expect(page.getByTestId("message")).toContainText(`${PASSWORD} was`);')).toHaveLength(1);
    expect(withFixtures('await page.goto(`/sign-on?user=x&pw=${PASSWORD}`);')).toHaveLength(1);
    expect(withFixtures('await expectMessage(page, PASSWORD);')).toHaveLength(1);
    expect(one("await page.goto('/reset?token=abc');")).toEqual(['a URL query that carries a secret']);
    expect(one("await expect(page).toHaveURL('/x?password=1');")).toEqual(['a URL query that carries a secret']);
  });

  it('refuses console output and attachments', () => {
    expect(one('console.log(value);')).toEqual(['console output lands in the report']);
    expect(one('process.stdout.write(value);')).toEqual(['console output lands in the report']);
    expect(one("await testInfo.attach('values', { body: value });")).toEqual(['an attachment lands in the report']);
    expect(one('test.info().attachments.push(a);')).toEqual(['an attachment lands in the report']);
  });
});
