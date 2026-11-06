import { Kbd } from '@mantine/core';
import { useFunctionKeys } from '@/prefs/FunctionKeys';

/** The function key of a button, shown only while the 3270 keys are on. */
export function KeyHint({ name }: { name: 'F3' | 'F4' }) {
  const { enabled } = useFunctionKeys();
  if (!enabled) return null;
  return (
    <Kbd size="xs" ml={8} aria-label={`shortcut ${name}`}>
      {name}
    </Kbd>
  );
}
