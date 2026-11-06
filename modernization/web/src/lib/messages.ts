import { messages, type MessageKey } from '@/messages';
import type { Severity } from '@/api/http';

export interface ScreenMessage {
  text: string;
  tone: Severity | 'notice';
}

/** The catalogue text of a key with its {placeholders} filled in. */
export function fillMessage(key: MessageKey, values: Record<string, string> = {}): string {
  return messages[key].text.replace(/\{(\w+)\}/g, (whole, key: string) => values[key] ?? whole);
}

/** A catalogue message ready for the message area. */
export function message(key: MessageKey, values?: Record<string, string>): ScreenMessage {
  return { text: fillMessage(key, values), tone: messages[key].severity };
}

/** The neutral note on /sign-on after the idle timeout or a refused refresh (catalogue SESSION_EXPIRED, shown as info). */
export const sessionEndedNote = { text: messages.SESSION_EXPIRED.text };

/**
 * Texts of the web app's own behaviour that the catalogue does not hold
 * (connection problems). They are never shown in place of a catalogue message.
 */
export const uiText = {
  noConnection: 'The service did not answer. Check your connection and try again.',
} as const;
