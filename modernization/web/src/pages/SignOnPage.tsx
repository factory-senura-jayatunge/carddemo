import { Button, PasswordInput, Stack, Text, TextInput } from '@mantine/core';
import { useRef, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { ApiError } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { message, type ScreenMessage } from '@/lib/messages';
import { useFieldFocus } from '@/lib/useFieldFocus';
import { messages } from '@/messages';
import { holdPasswordForChange } from '@/session/pendingPassword';
import { useSession, type SignOnRouteState, type SignedOffRouteState } from '@/session/SessionProvider';
import { checkSignOn } from '@/validation/rules';
import classes from './SignOnPage.module.css';

type Field = 'userId' | 'password';

export interface ChangePasswordRouteState {
  userId: string;
  message?: string;
}

function BrandPanel() {
  return (
    <div className={classes.brandPanel} aria-hidden="true">
      <div className={classes.brandCopy}>
        <p className={classes.headline}>Card operations, without the green screen.</p>
        <p className={classes.subline}>Accounts, cards, transactions and bill payments for CardDemo staff.</p>
      </div>
      <div className={classes.cardArt}>
        <div className={classes.card}>
          <div className={classes.cardChip} />
          <div className={classes.cardNumber}>•••• •••• •••• ••••</div>
          <div className={classes.cardFoot}>
            <span>CARDDEMO</span>
            <span>VALID THRU</span>
          </div>
        </div>
        <div className={`${classes.card} ${classes.cardBack}`} />
      </div>
    </div>
  );
}

/** COSGN00C as a web page: user ID, password, Sign on (Enter) and Exit (F3). */
export function SignOnPage() {
  const { state: session, signOn } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const routeState = (location.state ?? {}) as SignOnRouteState;

  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(
    routeState.note ? { text: routeState.note.text, tone: 'info' } : null,
  );
  const [errorField, setErrorField] = useState<Field | null>(null);
  const [busy, setBusy] = useState(false);
  const userIdRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const focus = useFieldFocus<Field>({ userId: userIdRef, password: passwordRef }, 'userId');

  const show = (next: ScreenMessage, field: Field | null) => {
    setScreenMessage(next);
    setErrorField(next.tone === 'error' ? field : null);
    if (field) focus(field);
  };

  const exit = () => {
    const state: SignedOffRouteState = { message: messages.SIGNED_OFF.text };
    void navigate('/signed-off', { replace: true, state });
  };

  usePageKeys({ back: exit, invalidKey: () => show(message('INVALID_KEY'), null) });

  if (session.status === 'authenticated' && !busy) return <Navigate to={session.user.home} replace />;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    const problem = checkSignOn(userId, password);
    if (problem) {
      show(message(problem.code), problem.field);
      return;
    }
    setBusy(true);
    try {
      const user = await signOn(userId, password);
      void navigate(routeState.from ?? user.home, { replace: true });
    } catch (error) {
      setBusy(false);
      if (error instanceof ApiError && error.code === 'PASSWORD_CHANGE_REQUIRED') {
        holdPasswordForChange(userId, password);
        const state: ChangePasswordRouteState = { userId: userId.trim().toUpperCase(), message: error.message };
        void navigate('/sign-on/change-password', { state });
        return;
      }
      if (error instanceof ApiError) {
        show({ text: error.message, tone: error.severity }, error.field === 'password' ? 'password' : 'userId');
      } else {
        show({ text: String(error), tone: 'error' }, 'userId');
      }
    }
  };

  const messageId = 'sign-on-message';
  const invalid = (field: Field) => (errorField === field ? true : undefined);

  return (
    <div className={classes.shell}>
      <BrandPanel />
      <section className={classes.formSide}>
        <div className={classes.formWrap}>
          <PageTitle title="Sign on" description="Use your CardDemo user ID and password." />
          <form onSubmit={(event) => void submit(event)} noValidate aria-describedby={messageId}>
            <Stack gap="lg">
              <TextInput
                ref={userIdRef}
                label="User ID"
                name="userId"
                value={userId}
                onChange={(event) => setUserId(event.currentTarget.value)}
                maxLength={8}
                autoComplete="username"
                autoCapitalize="characters"
                spellCheck={false}
                description="Up to 8 characters. Upper or lower case both work."
                error={invalid('userId')}
                aria-describedby={invalid('userId') ? messageId : undefined}
                classNames={{ input: 'cd-mono' }}
                data-testid="user-id"
              />
              <PasswordInput
                ref={passwordRef}
                label="Password"
                name="password"
                value={password}
                onChange={(event) => setPassword(event.currentTarget.value)}
                maxLength={64}
                autoComplete="current-password"
                description="Upper and lower case count."
                error={invalid('password')}
                aria-describedby={invalid('password') ? messageId : undefined}
                data-testid="password"
              />
              <MessageArea id={messageId} message={screenMessage} />
              <Button type="submit" size="md" fullWidth loading={busy}>
                Sign on
              </Button>
              <Button variant="subtle" color="slate" size="sm" onClick={exit} className={classes.exit}>
                Exit
                <KeyHint name="F3" />
              </Button>
            </Stack>
          </form>
          <Text size="xs" c="dimmed" mt={28}>
            Forgot your password? Ask a CardDemo administrator to reset it.
          </Text>
        </div>
      </section>
    </div>
  );
}
