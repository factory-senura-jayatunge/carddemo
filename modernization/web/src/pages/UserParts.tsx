import { Badge } from '@mantine/core';
import { useCallback, useEffect, useState } from 'react';
import type { UserView } from '@/api/client';
import { ApiError } from '@/api/http';
import { encodeUserKey } from '@/lib/userKey';
import type { ScreenMessage } from '@/lib/messages';

export const USERS_PATH = '/admin/users';
export const USER_ADD_PATH = '/admin/users/new';
export const PAGE_SIZE = 10;

/** The fields of the add and update forms, in the screen order the service checks them. */
export type UserField = 'firstName' | 'lastName' | 'userId' | 'password' | 'userType';
export const USER_FIELDS: UserField[] = ['firstName', 'lastName', 'userId', 'password', 'userType'];

/** The element id of a form field's input; for the type, its first choice. */
export const userFieldId = (field: UserField) => `user-${field}`;

/**
 * Moves focus to a field after the render that shows its message, found by its id: the
 * form's inputs live in a shared fields component, so there is no ref record to pass.
 */
export function useUserFieldFocus(initial: UserField) {
  const [request, setRequest] = useState<{ field: UserField; n: number }>({
    field: initial,
    n: 0,
  });
  useEffect(() => {
    document.getElementById(userFieldId(request.field))?.focus();
  }, [request]);
  return useCallback((field: UserField) => setRequest((current) => ({ field, n: current.n + 1 })), []);
}

export const userEditPath = (userId: string) => `${USERS_PATH}/${encodeUserKey(userId)}/edit`;
export const userDeletePath = (userId: string) => `${USERS_PATH}/${encodeUserKey(userId)}/delete`;

/** Route state of the user list: a result to show after a delete, or what admin options 3 and 4 opened it for. */
export interface UserListState {
  notice?: ScreenMessage;
  intent?: 'update' | 'delete';
}

/** The field a problem names, when it is one of the form's. */
export const fieldOf = (error: unknown, fields: readonly UserField[]): UserField | null =>
  error instanceof ApiError && (fields as readonly string[]).includes(error.field ?? '') ? (error.field as UserField) : null;

/** When a refusal in the batch window may be tried again (Retry-After). */
export const retryAtOf = (error: unknown): Date | null =>
  error instanceof ApiError && error.code === 'BATCH_WINDOW' ? new Date(Date.now() + (error.retryAfterSeconds ?? 300) * 1000) : null;

const hex = (n: number) => n.toString(16).toUpperCase().padStart(4, '0');

/**
 * A user ID as it is shown. An ID may hold controls, NUL and other characters with no
 * glyph; those are drawn as their Control Pictures symbol or as U+XXXX so the ID stays
 * readable and distinct. Only for display: links and API calls use the ID itself.
 */
export function printableId(id: string): string {
  return [...id]
    .map((c) => {
      const n = c.charCodeAt(0);
      if (n < 0x20) return String.fromCharCode(0x2400 + n);
      if (n === 0x7f) return '\u2421';
      if ((n >= 0x80 && n <= 0xa0) || n === 0xad) return `\u27e8U+${hex(n)}\u27e9`;
      return c;
    })
    .join('');
}

/** Admin, User, or a legacy type that cannot sign on. */
export function TypeBadge({ type, testId }: { type: string; testId?: string }) {
  if (type === 'A') {
    return (
      <Badge size="sm" radius="sm" variant="light" color="brand" data-testid={testId}>
        Administrator
      </Badge>
    );
  }
  if (type === 'U') {
    return (
      <Badge size="sm" radius="sm" variant="light" color="slate" data-testid={testId}>
        User
      </Badge>
    );
  }
  return (
    <Badge size="sm" radius="sm" variant="outline" color="red" data-testid={testId}>
      Type {printableId(type) || '(blank)'}
    </Badge>
  );
}

export const typeName = (type: string) => (type === 'A' ? 'Administrator' : type === 'U' ? 'User' : `Type ${printableId(type)}`);

export const fullName = (user: Pick<UserView, 'firstName' | 'lastName'>) => [user.firstName, user.lastName].filter((s) => s.trim() !== '').join(' ');
