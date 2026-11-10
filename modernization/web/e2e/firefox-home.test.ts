import { execFileSync } from 'node:child_process';
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createScratchHome, removeScratchHome } from './firefox-home';

// ACLs and chmod -h -N are macOS features; the scratch home is only made on macOS.
describe.skipIf(process.platform !== 'darwin')('Firefox scratch home on macOS', () => {
  let sandbox: string;
  let home: string;
  let outside: string;
  const acled: string[] = [];

  const denyDelete = (path: string) => {
    execFileSync('/bin/chmod', ['+a', 'group:everyone deny delete', path]);
    acled.push(path);
  };
  const hasDenyDelete = (path: string) => execFileSync('/bin/ls', ['-led', path]).toString().includes('deny delete');

  beforeEach(() => {
    sandbox = mkdtempSync(join(tmpdir(), 'carddemo-firefox-home-test-'));
    home = createScratchHome(sandbox);
    outside = join(sandbox, 'outside');
    mkdirSync(join(outside, 'sub'), { recursive: true });
    writeFileSync(join(outside, 'keep.txt'), 'keep');
    writeFileSync(join(outside, 'sub', 'keep.txt'), 'keep');
    denyDelete(outside);
    denyDelete(join(outside, 'sub'));
    denyDelete(join(outside, 'keep.txt'));
  });

  afterEach(() => {
    for (const path of acled.splice(0)) if (existsSync(path)) execFileSync('/bin/chmod', ['-h', '-N', path]);
    rmSync(sandbox, { recursive: true, force: true });
  });

  const firefoxFolders = () => {
    const support = join(home, 'Library', 'Application Support', 'Firefox');
    mkdirSync(support, { recursive: true });
    mkdirSync(join(home, 'Downloads'));
    writeFileSync(join(support, 'profiles.ini'), '');
    denyDelete(join(home, 'Library'));
    denyDelete(join(home, 'Library', 'Application Support'));
    denyDelete(join(home, 'Downloads'));
  };

  const expectOutsideUntouched = () => {
    expect(existsSync(join(outside, 'keep.txt'))).toBe(true);
    expect(existsSync(join(outside, 'sub', 'keep.txt'))).toBe(true);
    expect(hasDenyDelete(outside)).toBe(true);
    expect(hasDenyDelete(join(outside, 'sub'))).toBe(true);
    expect(hasDenyDelete(join(outside, 'keep.txt'))).toBe(true);
  };

  it('is a new private folder', () => {
    expect(basename(home)).toMatch(/^carddemo-pw-firefox-home-/);
    expect(lstatSync(home).mode & 0o777).toBe(0o700);
  });

  it('removes the deny-delete folders Firefox makes, which rmSync alone cannot', () => {
    firefoxFolders();
    expect(() => rmSync(home, { recursive: true, force: true })).toThrow();
    expect(removeScratchHome(home)).toBeUndefined();
    expect(existsSync(home)).toBe(false);
  });

  it('removes links to folders and files outside without touching them or their ACLs', () => {
    firefoxFolders();
    symlinkSync(outside, join(home, 'outside'));
    symlinkSync(join(outside, 'sub'), join(home, 'Library', 'sub'));
    symlinkSync(join(outside, 'keep.txt'), join(home, 'Downloads', 'keep.txt'));
    expect(removeScratchHome(home)).toBeUndefined();
    expect(existsSync(home)).toBe(false);
    expectOutsideUntouched();
  });

  it('removes broken links and links that loop back', () => {
    firefoxFolders();
    symlinkSync(join(sandbox, 'missing'), join(home, 'broken'));
    symlinkSync(join(sandbox, 'missing'), join(home, 'Library', 'broken'));
    symlinkSync(home, join(home, 'Library', 'Application Support', 'loop'));
    expect(removeScratchHome(home)).toBeUndefined();
    expect(existsSync(home)).toBe(false);
  });

  it('refuses a home that is itself a link', () => {
    const link = join(sandbox, 'home-link');
    symlinkSync(outside, link);
    expect(removeScratchHome(link)).toBe(`Firefox's scratch home ${link} was left behind (not a private folder of this user)`);
    expectOutsideUntouched();
  });

  it('refuses a home that others can read', () => {
    firefoxFolders();
    chmodSync(home, 0o755);
    expect(removeScratchHome(home)).toBe(`Firefox's scratch home ${home} was left behind (not a private folder of this user)`);
    expect(hasDenyDelete(join(home, 'Library'))).toBe(true);
  });

  it('warns with only the folder and an error code when removal fails', () => {
    firefoxFolders();
    const locked = join(home, 'Downloads');
    execFileSync('/usr/bin/chflags', ['uchg', locked]);
    try {
      expect(removeScratchHome(home)).toBe(`Firefox's scratch home ${home} was left behind (chmod failed)`);
    } finally {
      execFileSync('/usr/bin/chflags', ['nouchg', locked]);
    }
  });

  it('counts a home that is already gone as removed', () => {
    rmSync(home, { recursive: true });
    expect(removeScratchHome(home)).toBeUndefined();
  });

  it('relies on chmod -h -N changing a link and never its target', () => {
    const link = join(sandbox, 'link');
    symlinkSync(outside, link);
    execFileSync('/bin/chmod', ['-h', '-N', link]);
    expectOutsideUntouched();
  });
});
