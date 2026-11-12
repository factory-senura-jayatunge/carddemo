import { ESLint, Linter } from 'eslint';
import { resolve } from 'node:path';
import tseslint from 'typescript-eslint';
import { describe, expect, it } from 'vitest';

const web = resolve(import.meta.dirname, '../..');
const eslint = new ESLint({ cwd: web });

/** The project's no-restricted-imports setting for a file at the given path, or undefined when it is off there. */
async function ruleFor(path: string) {
  const config = (await eslint.calculateConfigForFile(path)) as { rules?: Record<string, Linter.RuleEntry> };
  return config.rules?.['no-restricted-imports'];
}

/** The no-restricted-imports findings for the code in a file at the given path, under the project's config for that path. */
async function restricted(path: string, code: string) {
  const rule = await ruleFor(path);
  if (rule === undefined) throw new Error(`no-restricted-imports is off for ${path}`);
  const config = { files: ['**/*.{ts,tsx}'], languageOptions: { parser: tseslint.parser }, rules: { 'no-restricted-imports': rule } } as Linter.Config;
  return new Linter({ cwd: web }).verify(code, [config], resolve(web, path)).map((m) => m.message);
}

describe('the import rule for the merged catalogue and the mocks', () => {
  it.each([
    "import { messages } from '@/messages/all';",
    "import { messages } from '../messages/all';",
    "import { messages } from '../../messages/all.ts';",
    "export { messages } from '@/messages/all';",
  ])('refuses the merged catalogue in app code: %s', async (code) => {
    expect(await restricted('src/pages/AnyPage.tsx', code)).toEqual([expect.stringContaining('The merged catalogue is for the mocks and tests')]);
  });

  it.each([
    "import { messages } from '@/mocks/messages';",
    "import { createMockServer } from '@/mocks/server';",
    "import { installMock } from './mocks/install';",
    "import { messages } from '../mocks/messages';",
    "import { messages } from '../../mocks/messages';",
  ])('refuses the mocks in app code: %s', async (code) => {
    expect(await restricted('src/lib/anything.ts', code)).toEqual([expect.stringContaining('The mocks are for mock mode and the tests')]);
  });

  it('allows the group tables, the types and names that only look alike', async () => {
    const code = [
      "import { cardMessages } from '@/messages/card';",
      "import type { EntryKey } from '@/messages';",
      "import { coreMessages } from '../messages/core';",
      "import { mockery } from '@/lib/mockery';",
      "import { overall } from '@/lib/overall';",
    ].join('\n');
    expect(await restricted('src/pages/AnyPage.tsx', code)).toEqual([]);
  });

  it('applies to app code and leaves the mocks and the tests free to use both', async () => {
    for (const path of ['src/main.tsx', 'src/pages/AnyPage.tsx', 'src/lib/messages.ts']) expect(await ruleFor(path)).toBeDefined();
    for (const path of ['src/mocks/server.ts', 'src/test/renderApp.tsx', 'src/pages/AnyPage.test.tsx']) expect(await ruleFor(path)).toBeUndefined();
  });
});
