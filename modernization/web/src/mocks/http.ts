import type { MessageKey } from '@/messages';
import { fillMessage, messages } from './messages';

/** Helpers the mock's route handlers share. */

export const decimal = (cents: number) => {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
};

export const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });

/** A problem response with a catalogue message; the API code is the key's code. */
export const problem = (
  status: number,
  key: MessageKey,
  field: string | null,
  extra: Record<string, unknown> = {},
  headers?: Record<string, string>,
  values?: Record<string, string>,
) =>
  json(
    status,
    { type: 'about:blank', status, code: messages[key].code, message: fillMessage(key, values), field, severity: messages[key].severity, ...extra },
    headers,
  );

/** A problem response for a failed rule of platform/validation's rule table: its code, text and field. */
export const ruleProblem = (status: number, failed: { code: string; message: { text: string }; field: string }, extra: Record<string, unknown> = {}) =>
  json(status, { type: 'about:blank', status, code: failed.code, message: failed.message.text, field: failed.field, severity: 'error', ...extra });

export const readBody = async (request: Request): Promise<Record<string, unknown>> => {
  try {
    const body: unknown = await request.json();
    return typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
  } catch {
    return {};
  }
};

export const text = (value: unknown) => (typeof value === 'string' ? value : '');
