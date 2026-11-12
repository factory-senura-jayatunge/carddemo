import { billMessages } from '@/messages/bill';
import { coreMessages } from '@/messages/core';
import type { EntryKey, MessageEntry, MessageTable } from '@/messages';
import type { Severity } from '@/api/http';

export interface ScreenMessage {
  text: string;
  tone: Severity | 'notice';
}

const entryMessages: MessageTable = { ...coreMessages, ...billMessages };

/**
 * message() and fillMessage() over the entry groups plus the group tables a lazy page passes in.
 * The page imports those tables itself, so they load with it and never with the entry
 * (scripts/check-bundle.mjs fails the build otherwise).
 */
export function catalogue<T extends MessageTable = Record<never, MessageEntry>>(table?: T) {
  type Key = EntryKey | (keyof T & string);
  const all = { ...entryMessages, ...table } as Record<Key, MessageEntry>;
  /** The catalogue text of a key with its {placeholders} filled in. */
  const fillMessage = (key: Key, values: Record<string, string> = {}): string =>
    all[key].text.replace(/\{(\w+)\}/g, (whole, name: string) => values[name] ?? whole);
  /** A catalogue message ready for the message area. */
  const message = (key: Key, values?: Record<string, string>): ScreenMessage => ({ text: fillMessage(key, values), tone: all[key].severity });
  return { message, fillMessage };
}

export const { message, fillMessage } = catalogue();

/** The neutral note on /sign-on after the idle timeout or a refused refresh (catalogue SESSION_EXPIRED, shown as info). */
export const sessionEndedNote = { text: coreMessages.SESSION_EXPIRED.text };

/**
 * Texts of the web app's own behaviour that the catalogue does not hold
 * (connection problems). They are never shown in place of a catalogue message.
 */
export const uiText = {
  noConnection: 'The service did not answer. Check your connection and try again.',
} as const;
