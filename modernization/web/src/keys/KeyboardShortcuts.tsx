import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useRef, type ReactNode } from 'react';
import { useFunctionKeys } from '@/prefs/FunctionKeys';

/**
 * The page actions the 3270 function keys trigger when the user has turned
 * them on (D-WEB-03): F3 Back or Exit, F4 Clear. Every other function key,
 * and F3 or F4 on a page without that action, calls invalidKey, which shows
 * "Invalid key pressed. Please see below..." and keeps the input
 * (VAL-BILL-006, VAL-SGN-005, VAL-MENU-002). With the toggle off the keys
 * keep their browser meaning.
 */
export interface KeyActions {
  back?: () => void;
  clear?: () => void;
  invalidKey: () => void;
}

interface Registry {
  register: (actions: KeyActions) => () => void;
}
const KeysContext = createContext<Registry | null>(null);

const FUNCTION_KEY = /^F([1-9]|1[0-2])$/;

function overlayOpen(): boolean {
  return document.querySelector('[role="dialog"][aria-modal="true"], [role="menu"], [role="listbox"]') !== null;
}

export function KeyboardShortcutsProvider({ children }: { children: ReactNode }) {
  const current = useRef<KeyActions | null>(null);
  const { enabled } = useFunctionKeys();
  const registry = useMemo<Registry>(
    () => ({
      register(actions) {
        current.current = actions;
        return () => {
          if (current.current === actions) current.current = null;
        };
      },
    }),
    [],
  );

  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.isComposing) return;
      if (!FUNCTION_KEY.test(event.key)) return;
      const actions = current.current;
      if (!actions || overlayOpen()) return;
      // F5 would reload the page and lose the input; F1, F6, F11 and F12 have
      // browser meanings too. With the toggle on, the page owns them all.
      event.preventDefault();
      const action = event.key === 'F3' ? actions.back : event.key === 'F4' ? actions.clear : undefined;
      (action ?? actions.invalidKey)();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled]);

  return <KeysContext.Provider value={registry}>{children}</KeysContext.Provider>;
}

/** Registers the current page's key actions while it is mounted. */
export function usePageKeys(actions: KeyActions): void {
  const registry = useContext(KeysContext);
  const latest = useRef(actions);
  useLayoutEffect(() => {
    latest.current = actions;
  });
  useEffect(() => {
    if (!registry) return;
    return registry.register({
      get back() {
        return latest.current.back;
      },
      get clear() {
        return latest.current.clear;
      },
      invalidKey: () => latest.current.invalidKey(),
    });
  }, [registry]);
}
