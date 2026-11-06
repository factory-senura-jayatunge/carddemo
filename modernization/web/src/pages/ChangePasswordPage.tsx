import { Button, Group, PasswordInput, Stack, TextInput } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { ApiError } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { message, type ScreenMessage } from '@/lib/messages';
import { useFieldFocus } from '@/lib/useFieldFocus';
import { forgetPasswordForChange, peekPasswordForChange } from '@/session/pendingPassword';
import { useSession } from '@/session/SessionProvider';
import { checkPasswordChange, NEW_PASSWORD_RULE } from '@/validation/rules';
import type { ChangePasswordRouteState } from './SignOnPage';
import classes from './SignOnPage.module.css';

type Field = 'userId' | 'currentPassword' | 'newPassword' | 'confirmPassword';

const SERVER_FIELDS: Record<string, Field> = {
  userId: 'userId',
  password: 'currentPassword',
  currentPassword: 'currentPassword',
  newPassword: 'newPassword',
};

/**
 * The forced password change (D-SEC-07), reached when sign-on answers
 * PASSWORD_CHANGE_REQUIRED. A successful change signs the user on.
 */
export function ChangePasswordPage() {
  const { state: session, changePassword } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const routeState = (location.state ?? null) as ChangePasswordRouteState | null;
  const knownUser = routeState?.userId ?? '';

  const [userId, setUserId] = useState(knownUser);
  const [currentPassword, setCurrentPassword] = useState(() => peekPasswordForChange(knownUser));
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(
    routeState?.message ? { text: routeState.message, tone: 'info' } : message('PASSWORD_CHANGE_REQUIRED'),
  );
  const [errorField, setErrorField] = useState<Field | null>(null);
  const [busy, setBusy] = useState(false);
  const userIdRef = useRef<HTMLInputElement>(null);
  const currentRef = useRef<HTMLInputElement>(null);
  const newRef = useRef<HTMLInputElement>(null);
  const confirmRef = useRef<HTMLInputElement>(null);
  const focus = useFieldFocus<Field>(
    { userId: userIdRef, currentPassword: currentRef, newPassword: newRef, confirmPassword: confirmRef },
    knownUser ? (currentPassword ? 'newPassword' : 'currentPassword') : 'userId',
  );

  useEffect(() => forgetPasswordForChange, []);

  const show = (next: ScreenMessage, field: Field | null) => {
    setScreenMessage(next);
    setErrorField(next.tone === 'error' ? field : null);
    if (field) focus(field);
  };

  const back = () => void navigate('/sign-on', { replace: true });
  usePageKeys({ back, invalidKey: () => show(message('INVALID_KEY'), null) });

  if (session.status === 'authenticated' && !busy) return <Navigate to={session.user.home} replace />;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    const problem = checkPasswordChange(userId, currentPassword, newPassword, confirmPassword);
    if (problem) {
      show(message(problem.code), problem.field);
      return;
    }
    setBusy(true);
    try {
      const user = await changePassword(userId, currentPassword, newPassword);
      void navigate(user.home, { replace: true });
    } catch (error) {
      setBusy(false);
      if (error instanceof ApiError) {
        show({ text: error.message, tone: error.severity }, SERVER_FIELDS[error.field ?? ''] ?? 'newPassword');
      } else {
        show({ text: String(error), tone: 'error' }, 'newPassword');
      }
    }
  };

  const messageId = 'change-password-message';
  const ruleId = 'new-password-rule';
  const describedBy = (field: Field, ...extra: string[]) =>
    [...extra, errorField === field ? messageId : null].filter(Boolean).join(' ') || undefined;
  const invalid = (field: Field) => (errorField === field ? true : undefined);

  return (
    <div className={classes.narrow}>
      <PageTitle title="Change your password" description="Your password must be changed before you can continue." />
      <form onSubmit={(event) => void submit(event)} noValidate>
        <Stack gap="lg">
          {knownUser ? (
            <input type="text" name="username" autoComplete="username" value={knownUser} readOnly hidden />
          ) : (
            <TextInput
              ref={userIdRef}
              label="User ID"
              value={userId}
              onChange={(event) => setUserId(event.currentTarget.value)}
              maxLength={8}
              autoComplete="username"
              spellCheck={false}
              classNames={{ input: 'cd-mono' }}
              error={invalid('userId')}
              aria-describedby={describedBy('userId')}
            />
          )}
          <PasswordInput
            ref={currentRef}
            label="Current password"
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.currentTarget.value)}
            maxLength={64}
            autoComplete="current-password"
            error={invalid('currentPassword')}
            aria-describedby={describedBy('currentPassword')}
            data-testid="current-password"
          />
          <PasswordInput
            ref={newRef}
            label="New password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.currentTarget.value)}
            maxLength={64}
            autoComplete="new-password"
            description={<span id={ruleId}>{NEW_PASSWORD_RULE}</span>}
            error={invalid('newPassword')}
            aria-describedby={describedBy('newPassword', ruleId)}
            data-testid="new-password"
          />
          <PasswordInput
            ref={confirmRef}
            label="Repeat new password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.currentTarget.value)}
            maxLength={64}
            autoComplete="new-password"
            error={invalid('confirmPassword')}
            aria-describedby={describedBy('confirmPassword')}
            data-testid="confirm-password"
          />
          <MessageArea id={messageId} message={screenMessage} />
          <Group gap="sm" grow>
            <Button variant="default" size="md" onClick={back} disabled={busy}>
              Back
              <KeyHint name="F3" />
            </Button>
            <Button type="submit" size="md" loading={busy}>
              Change password
            </Button>
          </Group>
        </Stack>
      </form>
    </div>
  );
}
