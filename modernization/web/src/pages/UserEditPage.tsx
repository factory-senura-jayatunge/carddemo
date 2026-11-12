import { Button, Loader, Text } from '@mantine/core';
import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useParams } from 'react-router';
import { identityApi, type UpdateUserRequest, type UserView } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { LeaveGuard } from '@/components/LeaveGuard';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatClockTime } from '@/lib/format';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { userMessages } from '@/messages/user';
import { useBack } from '@/lib/useBack';
import { problemMessage } from '@/lib/useReveal';
import { decodeUserKey } from '@/lib/userKey';
import { useFunctionKeys } from '@/prefs/FunctionKeys';
import { useUiSwitch } from '@/session/features';
import { userFormRules, userProblem } from '@/validation/userRules';
import { EMPTY_USER, UserFormFields, type UserValues } from './UserFormFields';
import { fieldOf, printableId, retryAtOf, typeName, USERS_PATH, useUserFieldFocus, type UserField } from './UserParts';
import { MissingUser, useUserRecord } from './useUserRecord';
import classes from './records.module.css';
import users from './UserPages.module.css';

const { message } = catalogue(userMessages);

const EDITABLE: UserField[] = ['firstName', 'lastName', 'password', 'userType'];
/** Form userUpdate: its values are the service's (names without the U+0020 pad, a password without spaces and NUL). */
const updateRules = () => userFormRules('update', false);
/** Only spaces and NUL keep the current password. */
const blankPassword = (s: string) => updateRules().value('password', s) === '';

const valuesOf = (user: UserView): UserValues => ({
  ...EMPTY_USER,
  firstName: user.firstName,
  lastName: user.lastName,
  userId: user.userId,
  userType: user.userType,
});

/** BR-USRU-004 as the service compares: names without their trailing pad, the type, and a given password always counts. */
export const isUnchanged = (values: UserValues, user: UserView) => {
  const rules = updateRules();
  return (
    rules.value('firstName', values.firstName) === rules.value('firstName', user.firstName) &&
    rules.value('lastName', values.lastName) === rules.value('lastName', user.lastName) &&
    values.userType === user.userType &&
    blankPassword(values.password)
  );
};

/**
 * Whether the page itself answers "no change": the service checks the fields before it compares
 * (VAL-USRU-002 before BR-USRU-004), so only values that pass form userUpdate get it here. The
 * password is blank then, so the fixed and legacy forms agree.
 */
export const pageSaysNoChange = (values: UserValues, user: UserView) =>
  isUnchanged(values, user) && userProblem('update', false, { ...values, userId: user.userId }) === null;

/** COUSR02C as a web page: the user a route names, its ID locked, saved with If-Match. */
export function UserEditPage() {
  const { userKey = '' } = useParams();
  return <UserEdit key={userKey} userKey={userKey} />;
}

function UserEdit({ userKey }: { userKey: string }) {
  const back = useBack(USERS_PATH);
  const [record, setRecord] = useState<UserView | null>(null);
  const [values, setValues] = useState<UserValues>(EMPTY_USER);
  const [passwordOpen, setPasswordOpen] = useState(false);
  /** What someone else saved, after 409 RECORD_CHANGED; the user's edits stay (D-17). */
  const [current, setCurrent] = useState<UserView | null>(null);
  const [moved, setMoved] = useState<UserField[]>([]);
  const [errorField, setErrorField] = useState<UserField | null>(null);
  const [saved, setSaved] = useState(false);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(() =>
    decodeUserKey(userKey) === null ? message('USER_ID_NOT_FOUND') : null,
  );
  const [retryAt, setRetryAt] = useState<Date | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const focus = useUserFieldFocus('firstName');
  const { enabled: keysOn } = useFunctionKeys();
  const legacyPasswords = useUiSwitch('DEF-SGN-011') === 'legacy';

  const show = (user: UserView) => {
    setRecord(user);
    setValues(valuesOf(user));
    setPasswordOpen(false);
    setCurrent(null);
    setMoved([]);
    setErrorField(null);
    setRetryAt(null);
  };

  const { stage, retryable, reload } = useUserRecord(userKey, {
    onLoaded: (user) => {
      show(user);
      setSaved(false);
      setScreenMessage(null);
      focus('firstName');
    },
    onFailed: setScreenMessage,
  });

  const change = (field: UserField, value: string) => {
    setValues((v) => ({ ...v, [field]: value }));
    setSaved(false);
  };

  const discard = () => {
    if (!record) return;
    show(record);
    setScreenMessage(null);
    focus('firstName');
  };

  const save = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!record || busyRef.current) return;
    if (pageSaysNoChange(values, record)) {
      setErrorField(null);
      setScreenMessage(message('USER_NO_CHANGE'));
      focus('firstName');
      return;
    }
    const sent = passwordOpen ? values.password : '';
    const failed = userProblem<UserField>('update', legacyPasswords, { ...values, userId: record.userId, password: sent });
    if (failed) {
      setErrorField(failed.field);
      setRetryAt(null);
      setScreenMessage(failed.message);
      focus(failed.field);
      return;
    }
    const base = record;
    const body: UpdateUserRequest = {
      firstName: values.firstName,
      lastName: values.lastName,
      userType: values.userType as UpdateUserRequest['userType'],
      ...(passwordOpen && !blankPassword(values.password) ? { password: values.password } : {}),
    };
    busyRef.current = true;
    setBusy(true);
    try {
      const result = await unwrap(
        identityApi.PUT('/api/users/key/{userKey}', {
          params: {
            path: { userKey },
            header: { 'If-Match': `"${base.version}"` },
          },
          body,
        }),
      );
      show(result.user);
      setSaved(true);
      setScreenMessage({ text: result.message, tone: result.severity });
    } catch (error) {
      if (error instanceof ApiError && error.code === 'RECORD_CHANGED' && error.body.current) {
        const latest = error.body.current as UserView;
        setMoved((['firstName', 'lastName', 'userType'] as const).filter((f) => latest[f] !== base[f]));
        setCurrent(latest);
        setRecord(latest);
        setErrorField(null);
        setRetryAt(null);
        setScreenMessage(problemMessage(error));
        focus('firstName');
        return;
      }
      const field = fieldOf(error, EDITABLE);
      setErrorField(field);
      setRetryAt(retryAtOf(error));
      setScreenMessage(problemMessage(error));
      if (field === 'password') setPasswordOpen(true);
      focus(field ?? 'firstName');
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  usePageKeys({
    back,
    escapeBack: true,
    save: stage === 'ready' ? () => void save() : undefined,
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const messageId = 'user-edit-message';
  const dirty = stage === 'ready' && record !== null && !isUnchanged(values, record);
  // The prompt names PF5, so it only shows with the function keys on; otherwise Save says it.
  const prompt = keysOn && stage === 'ready' && !saved ? message('USER_UPDATE_PROMPT') : null;
  const note = (field: 'firstName' | 'lastName' | 'userType'): ReactNode =>
    current && moved.includes(field) ? (
      <span className={classes.current} data-testid={`${field}-current`}>
        Now saved as <span className="cd-mono">{field === 'userType' ? typeName(current.userType) : current[field] || '(blank)'}</span>
      </span>
    ) : null;

  return (
    <>
      <LeaveGuard dirty={dirty} />
      <PageTitle
        title="Update user"
        description="Change a user's names or type, or set a new password. The user ID cannot be changed."
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      {stage === 'loading' && (
        <div className={classes.searchCard} aria-busy="true">
          <Loader size="sm" aria-label="Loading the user" />
        </div>
      )}
      {stage === 'missing' && <MissingUser retryable={retryable} onRetry={() => void reload()} onBack={back} backLabel="Back to users" />}
      {stage === 'ready' && record && (
        <form className={classes.searchCard} onSubmit={(event) => void save(event)} noValidate aria-label={`User ${printableId(record.userId)}`}>
          <UserFormFields
            values={values}
            onChange={change}
            errorField={errorField}
            messageId={messageId}

            storedType={record.userType}
            notes={{
              firstName: note('firstName'),
              lastName: note('lastName'),
              userType: note('userType'),
            }}
            idSlot={
              <div className={users.lockedId}>
                <span className={users.lockedIdLabel}>User ID</span>
                <span className={users.lockedIdValue} data-testid="locked-id">
                  {printableId(record.userId)}
                </span>
              </div>
            }
            showPassword={passwordOpen}
            passwordSlot={
              <Button
                variant="subtle"
                size="compact-sm"
                className={users.passwordToggle}
                onClick={() => {
                  if (passwordOpen) change('password', '');
                  else focus('password');
                  setPasswordOpen(!passwordOpen);
                }}
                aria-expanded={passwordOpen}
                data-testid="password-toggle"
              >
                {passwordOpen ? 'Keep the current password' : 'Set a new password'}
              </Button>
            }
          />
          {prompt && (
            <p className={classes.infoLine} data-testid="info-line">
              {prompt.text}
            </p>
          )}
          <div className={classes.formActions}>
            <Button variant="default" onClick={discard} disabled={busy || !dirty} data-testid="discard">
              Discard changes
            </Button>
            <Button type="submit" loading={busy} data-testid="save">
              Save
              <KeyHint name="F5" />
            </Button>
          </div>
        </form>
      )}
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
