import { messages, type MessageCode } from '@/messages';
import type { Severity } from '@/api/http';

export interface ScreenMessage {
  text: string;
  tone: Severity | 'notice';
}

/** The catalogue text of a code with its {placeholders} filled in. */
export function fillMessage(code: MessageCode, values: Record<string, string> = {}): string {
  return messages[code].text.replace(/\{(\w+)\}/g, (whole, key: string) => values[key] ?? whole);
}

/** A catalogue message ready for the message area. */
export function message(code: MessageCode, values?: Record<string, string>): ScreenMessage {
  return { text: fillMessage(code, values), tone: messages[code].severity };
}

/**
 * Texts of the web app's own behaviour, which has no legacy counterpart
 * (session handling and connection problems). They are never shown in place
 * of a catalogue message.
 */
export const uiText = {
  sessionExpired: 'Your session ended. Sign on again to continue.',
  idleSignedOut: 'You were signed out after 15 minutes without activity.',
  noConnection: 'The service did not answer. Check your connection and try again.',
} as const;
