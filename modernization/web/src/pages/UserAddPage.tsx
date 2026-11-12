import { Button, Text, TextInput } from '@mantine/core';
import { useRef, useState, type FormEvent } from 'react';
import { identityApi, type NewUserRequest } from '@/api/client';
import { unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { LeaveGuard } from '@/components/LeaveGuard';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatClockTime } from '@/lib/format';
import { message, type ScreenMessage } from '@/lib/messages';
import { useBack } from '@/lib/useBack';
import { problemMessage } from '@/lib/useReveal';
import { useUiSwitch } from '@/session/features';
import { userProblem } from '@/validation/userRules';
import { EMPTY_USER, UserFormFields, type UserValues } from './UserFormFields';
import { fieldOf, retryAtOf, USER_FIELDS, userFieldId, useUserFieldFocus, type UserField } from './UserParts';
import classes from './records.module.css';

/** COUSR01C as a web page: a new user's names, ID, password and type, added in one step. */
export function UserAddPage() {
  const back = useBack('/admin');
  const [values, setValues] = useState<UserValues>(EMPTY_USER);
  const [errorField, setErrorField] = useState<UserField | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [retryAt, setRetryAt] = useState<Date | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const focus = useUserFieldFocus('firstName');
  const legacyPasswords = useUiSwitch('DEF-SGN-011') === 'legacy';

  const change = (field: UserField, value: string) => setValues((current) => ({ ...current, [field]: value }));

  const clear = () => {
    setValues(EMPTY_USER);
    setErrorField(null);
    setScreenMessage(null);
    setRetryAt(null);
    focus('firstName');
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    const failed = userProblem<UserField>('add', legacyPasswords, values);
    if (failed) {
      setErrorField(failed.field);
      setRetryAt(null);
      setScreenMessage(failed.message);
      focus(failed.field);
      return;
    }
    busyRef.current = true;
    setBusy(true);
    try {
      // As typed: the service decides the canonical ID and repeats the checks.
      const result = await unwrap(identityApi.POST('/api/users', { body: values as NewUserRequest }));
      setValues(EMPTY_USER);
      setErrorField(null);
      setRetryAt(null);
      setScreenMessage({ text: result.message, tone: result.severity });
      focus('firstName');
    } catch (error) {
      const field = fieldOf(error, USER_FIELDS);
      setErrorField(field);
      setRetryAt(retryAtOf(error));
      setScreenMessage(problemMessage(error));
      focus(field ?? 'firstName');
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  usePageKeys({
    back,
    escapeBack: true,
    clear,
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const messageId = 'user-add-message';
  const dirty = Object.values(values).some((v) => v !== '');

  return (
    <>
      <LeaveGuard dirty={dirty} />
      <PageTitle
        title="Add user"
        description="Give a person a user ID and a first password. They can sign on as soon as the user is added."
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form className={classes.searchCard} onSubmit={(event) => void submit(event)} noValidate aria-label="New user">
        <UserFormFields
          values={values}
          onChange={change}
          errorField={errorField}
          messageId={messageId}

          showPassword
          idSlot={
            <TextInput
              id={userFieldId('userId')}
              label="User ID"
              value={values.userId}
              onChange={(event) => change('userId', event.currentTarget.value)}
              autoComplete="off"
              spellCheck={false}
              description={<span id="user-id-rule">Up to 8 characters, no spaces. Letters a to z are saved in capitals.</span>}
              error={errorField === 'userId' ? true : undefined}
              aria-describedby={['user-id-rule', errorField === 'userId' ? messageId : null].filter(Boolean).join(' ')}
              classNames={{ input: classes.keyInput }}
              data-testid="userId"
            />
          }
        />
        <div className={classes.formActions}>
          <Button variant="default" onClick={clear} disabled={busy} data-testid="clear">
            Clear
            <KeyHint name="F4" />
          </Button>
          <Button type="submit" loading={busy} data-testid="submit">
            Add user
          </Button>
        </div>
      </form>
      <div className={classes.messageSlot}>
        <MessageArea id={messageId} message={screenMessage}>
          {retryAt && (
            <Text size="sm" mt={4} c="inherit">
              Try again after {formatClockTime(retryAt)}.
            </Text>
          )}
        </MessageArea>
      </div>
    </>
  );
}
