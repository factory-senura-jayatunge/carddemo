import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useRef, type ReactNode } from 'react';
import { useFunctionKeys } from '@/prefs/FunctionKeys';

/**
 * The page actions the 3270 function keys trigger when the user has turned
 * them on (D-WEB-03): F3 Back or Exit, F4 Clear, F5 Save, F7 Previous, F8
 * Next and, only while a confirmation step is showing, F12 Cancel. Every other function key, and a key the page has no action for,
 * calls invalidKey, which shows "Invalid key pressed. Please see below..." and
 * keeps the input (VAL-BILL-006, VAL-SGN-005, VAL-MENU-002). With the toggle
 * off the function keys keep their browser meaning. Escape is Back on the
 * pages that ask for it (escapeBack), whatever the toggle says.
 */
export interface KeyActions {
  back?: () => void;
  clear?: () => void;
  save?: () => void;
  previous?: () => void;
  next?: () => void;
  cancel?: () => void;
  escapeBack?: boolean;
  invalidKey: () => void;
}

interface Registry {
  register: (actions: KeyActions) => () => void;
}
const KeysContext = createContext<Registry | null>(null);

const FUNCTION_KEY = /^F([1-9]|1[0-2])$/;
const ACTION_OF: Partial<Record<string, Exclude<keyof KeyActions, 'invalidKey' | 'escapeBack'>>> = {
  F3: 'back',
  F4: 'clear',
  F5: 'save',
  F7: 'previous',
  F8: 'next',
  F12: 'cancel',
};

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
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.isComposing) return;
      const actions = current.current;
      if (!actions || overlayOpen()) return;
      if (event.key === 'Escape') {
        if (!actions.escapeBack || !actions.back || event.defaultPrevented) return;
        event.preventDefault();
        actions.back();
        return;
      }
      if (!enabled || !FUNCTION_KEY.test(event.key)) return;
      // F5 would reload the page and lose the input; F1, F6, F11 and F12 have
      // browser meanings too. With the toggle on, the page owns them all.
      event.preventDefault();
      const name = ACTION_OF[event.key];
      const action = name ? actions[name] : undefined;
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
  // A layout effect registers in the commit that puts the page on screen; a passive one runs a task
  // later, and a key pressed as soon as the page appears would go to no page at all.
  useLayoutEffect(() => {
    if (!registry) return;
    return registry.register({
      get back() {
        return latest.current.back;
      },
      get clear() {
        return latest.current.clear;
      },
      get save() {
        return latest.current.save;
      },
      get previous() {
        return latest.current.previous;
      },
      get next() {
        return latest.current.next;
      },
      get cancel() {
        return latest.current.cancel;
      },
      get escapeBack() {
        return latest.current.escapeBack;
      },
      invalidKey: () => latest.current.invalidKey(),
    });
  }, [registry]);
}
