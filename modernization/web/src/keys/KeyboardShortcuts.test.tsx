import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { KeyboardShortcutsProvider, usePageKeys } from './KeyboardShortcuts';

const actEnvironment = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };
let root: Root | null = null;
let container: HTMLElement | null = null;
let actBefore: boolean | undefined;

afterEach(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true;
  act(() => root?.unmount());
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = actBefore;
  container?.remove();
  root = null;
  container = null;
});

function Page({ back }: { back: () => void }) {
  usePageKeys({ back, escapeBack: true, invalidKey: () => undefined });
  return <h1>Page</h1>;
}

describe('usePageKeys', () => {
  it("a page's keys work from the moment the page is on screen", async () => {
    const back = vi.fn();
    actBefore = actEnvironment.IS_REACT_ACT_ENVIRONMENT;
    actEnvironment.IS_REACT_ACT_ENVIRONMENT = true;
    container = document.createElement('div');
    document.body.append(container);
    const mounted = createRoot(container);
    root = mounted;
    act(() => mounted.render(<KeyboardShortcutsProvider>{null}</KeyboardShortcutsProvider>));

    // The page arrives the way a route change brings it, outside act: React commits it, then runs
    // its passive effects in a later task. A key pressed in between, as soon as the heading is in
    // the document, must still reach the page.
    actEnvironment.IS_REACT_ACT_ENVIRONMENT = false;
    const target = container;
    const pressed = new Promise<void>((resolve) => {
      const observer = new MutationObserver(() => {
        if (!target.querySelector('h1')) return;
        observer.disconnect();
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }));
        resolve();
      });
      observer.observe(target, { childList: true, subtree: true });
    });
    mounted.render(
      <KeyboardShortcutsProvider>
        <Page back={back} />
      </KeyboardShortcutsProvider>,
    );
    await pressed;
    actEnvironment.IS_REACT_ACT_ENVIRONMENT = true;
    expect(back).toHaveBeenCalledTimes(1);
  });
});
