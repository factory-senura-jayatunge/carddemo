import { Kbd } from '@mantine/core';
import { useFunctionKeys } from '@/prefs/FunctionKeys';

export type FunctionKeyName = 'F3' | 'F4' | 'F5' | 'F7' | 'F8' | 'F12';

/** The function key of a button, shown only while the 3270 keys are on. */
export function KeyHint({ name }: { name: FunctionKeyName }) {
  const { enabled } = useFunctionKeys();
  if (!enabled) return null;
  return (
    <Kbd size="xs" ml={8} aria-label={`shortcut ${name}`}>
      {name}
    </Kbd>
  );
}
