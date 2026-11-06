import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

/** localStorage key of the 3270 function-key toggle (D-WEB-03). */
export const FKEYS_STORAGE_KEY = 'carddemo.fkeys';

interface FunctionKeysApi {
  enabled: boolean;
  setEnabled: (enabled: boolean) => void;
}

const FunctionKeysContext = createContext<FunctionKeysApi>({ enabled: false, setEnabled: () => undefined });

function load(): boolean {
  try {
    return localStorage.getItem(FKEYS_STORAGE_KEY) === 'on';
  } catch {
    return false;
  }
}

/** The 3270 function-key toggle, off by default and kept in this browser. */
export function FunctionKeysProvider({ children }: { children: ReactNode }) {
  const [enabled, setState] = useState(load);
  const setEnabled = useCallback((next: boolean) => {
    setState(next);
    try {
      localStorage.setItem(FKEYS_STORAGE_KEY, next ? 'on' : 'off');
    } catch {
      // Storage can be unavailable (private mode); the setting then lasts for this page.
    }
  }, []);
  const value = useMemo(() => ({ enabled, setEnabled }), [enabled, setEnabled]);
  return <FunctionKeysContext.Provider value={value}>{children}</FunctionKeysContext.Provider>;
}

export const useFunctionKeys = () => useContext(FunctionKeysContext);
