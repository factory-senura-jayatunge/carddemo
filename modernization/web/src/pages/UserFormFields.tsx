import { PasswordInput, Radio, TextInput } from '@mantine/core';
import type { ReactNode } from 'react';
import { useUiSwitch } from '@/session/features';
import { printableId, userFieldId, type UserField } from './UserParts';
import users from './UserPages.module.css';

export type UserValues = Record<UserField, string>;
export const EMPTY_USER: UserValues = {
  firstName: '',
  lastName: '',
  userId: '',
  password: '',
  userType: '',
};

interface Props {
  values: UserValues;
  onChange: (field: UserField, value: string) => void;
  errorField: UserField | null;
  messageId: string;
  /** The user ID: a field on add, the locked ID on update. */
  idSlot: ReactNode;
  /** Update hides the password behind a control; add always shows it. */
  showPassword: boolean;
  passwordSlot?: ReactNode;
  /** What someone else saved, under a field, after a version conflict. */
  notes?: Partial<Record<UserField, ReactNode>>;
  /** A stored type the form cannot keep (a legacy user): shown so the admin knows to choose. */
  storedType?: string;
}

/**
 * The fields of the add and update forms in the screen order the service checks them:
 * first name, last name, user ID, password, type. Values go to the service as typed.
 */
export function UserFormFields({ values, onChange, errorField, messageId, idSlot, showPassword, passwordSlot, notes = {}, storedType }: Props) {
  const legacyPasswords = useUiSwitch('DEF-SGN-011') === 'legacy';
  const invalid = (field: UserField) => (errorField === field ? true : undefined);
  const describedBy = (field: UserField, ...extra: string[]) => [...extra, errorField === field ? messageId : null].filter(Boolean).join(' ') || undefined;
  const text = (field: 'firstName' | 'lastName', label: string) => (
    <div>
      <TextInput
        id={userFieldId(field)}
        label={label}
        value={values[field]}
        onChange={(event) => onChange(field, event.currentTarget.value)}
        maxLength={20}
        autoComplete="off"
        spellCheck={false}
        error={invalid(field)}
        aria-describedby={describedBy(field)}
        data-testid={field}
      />
      {notes[field]}
    </div>
  );
  return (
    <div className={users.formStack}>
      {text('firstName', 'First name')}
      {text('lastName', 'Last name')}
      {idSlot}
      {showPassword ? (
        <PasswordInput
          id={userFieldId('password')}
          label={passwordSlot ? 'New password' : 'Password'}
          value={values.password}
          onChange={(event) => onChange('password', event.currentTarget.value)}
          autoComplete="new-password"
          description={
            <span id="user-password-rule">
              {legacyPasswords
                ? '1 to 8 characters. Letters are saved in capitals.'
                : '8 to 64 characters. Upper and lower case count. It must not be the user ID.'}
            </span>
          }
          error={invalid('password')}
          aria-invalid={invalid('password')}
          aria-describedby={describedBy('password', 'user-password-rule')}
          data-testid="password"
        />
      ) : (
        <div />
      )}
      {passwordSlot && <div className={users.full}>{passwordSlot}</div>}
      <div className={users.full}>
        <Radio.Group
          label="User type"
          value={values.userType}
          onChange={(value) => onChange('userType', value)}
          error={invalid('userType')}
          aria-describedby={describedBy('userType')}
          data-testid="userType"
        >
          <div className={users.typeRow}>
            <Radio
              id={userFieldId('userType')}
              value="A"
              label="Administrator"
              description="Admin menu: users, and read-only account and card views."
              data-testid="type-admin"
            />
            <Radio value="U" label="User" description="Main menu: accounts, cards and bill payment." data-testid="type-user" />
          </div>
        </Radio.Group>
        {storedType !== undefined && storedType !== 'A' && storedType !== 'U' && (
          <p className={users.legacyType} data-testid="stored-type">
            Stored type {printableId(storedType) || '(blank)'}, which cannot sign on. Choose Administrator or User to save this user.
          </p>
        )}
        {notes.userType}
      </div>
    </div>
  );
}
