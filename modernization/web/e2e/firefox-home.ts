import { execFileSync } from 'node:child_process';
import { lstatSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** A new empty folder, mode 700, under parent (the user's temp folder by default). */
export function createScratchHome(parent = tmpdir()): string {
  return mkdtempSync(join(parent, 'carddemo-pw-firefox-home-'));
}

/**
 * chmod -N on dir and the real folders below it. Symbolic links are never followed: listings skip
 * them, and -h makes chmod change a link itself should one take a folder's place after the listing.
 */
function removeAcls(dir: string) {
  execFileSync('/bin/chmod', ['-h', '-N', dir], { stdio: 'ignore' });
  if (!lstatSync(dir).isDirectory()) return;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) removeAcls(join(dir, entry.name));
  }
}

/**
 * Removes a folder made by createScratchHome, including the Library and Downloads folders that
 * Firefox creates there with a macOS "deny delete" ACL. Only a real folder of this user with mode 700
 * is touched, so nobody else can have put a link in it. Returns undefined once the folder is gone,
 * otherwise a one-line warning that names the folder and an error code and nothing else.
 */
export function removeScratchHome(home: string): string | undefined {
  const leftBehind = (reason: string) => `Firefox's scratch home ${home} was left behind (${reason})`;
  try {
    const root = lstatSync(home);
    if (!root.isDirectory() || root.uid !== process.getuid?.() || (root.mode & 0o777) !== 0o700) {
      return leftBehind('not a private folder of this user');
    }
    removeAcls(home);
    rmSync(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    return undefined;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === 'ENOENT' && !exists(home)) return undefined;
    return leftBehind(code ?? 'chmod failed');
  }
}

function exists(path: string) {
  try {
    lstatSync(path);
    return true;
  } catch {
    return false;
  }
}
