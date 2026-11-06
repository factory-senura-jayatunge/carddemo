import { Badge, Button, TextInput } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { message, type ScreenMessage } from '@/lib/messages';
import { displayName } from '@/lib/names';
import { useFeatures } from '@/session/features';
import { useSession, useUser } from '@/session/SessionProvider';
import { parseMenuOption } from '@/validation/rules';
import classes from './MenuPage.module.css';

type OptionStatus = 'available' | 'coming-soon' | 'not-installed';

interface MenuOption {
  number: number;
  name: string;
  /** Route of a converted option. */
  to?: string;
  /** The optional module the option belongs to. */
  module?: 'authorization';
}

/** COMEN02Y, in order (BR-MENU-001). */
export const MAIN_OPTIONS: MenuOption[] = [
  { number: 1, name: 'Account View' },
  { number: 2, name: 'Account Update' },
  { number: 3, name: 'Credit Card List' },
  { number: 4, name: 'Credit Card View' },
  { number: 5, name: 'Credit Card Update' },
  { number: 6, name: 'Transaction List' },
  { number: 7, name: 'Transaction View' },
  { number: 8, name: 'Transaction Add' },
  { number: 9, name: 'Transaction Reports' },
  { number: 10, name: 'Bill Payment', to: '/bill-pay' },
  { number: 11, name: 'Pending Authorization View', module: 'authorization' },
];

/** COADM02Y options 1 to 4; 5 and 6 belong to a module that is not installed and are hidden (D-70). */
export const ADMIN_OPTIONS: MenuOption[] = [
  { number: 1, name: 'User List (Security)' },
  { number: 2, name: 'User Add (Security)' },
  { number: 3, name: 'User Update (Security)' },
  { number: 4, name: 'User Delete (Security)' },
];

const STATUS_LABEL: Record<OptionStatus, string> = {
  available: 'Open',
  'coming-soon': 'Coming soon',
  'not-installed': 'Not installed',
};

const two = (n: number) => String(n).padStart(2, '0');

interface MenuPageProps {
  variant: 'main' | 'admin';
}

/** COMEN01C (main menu) and the COADM01C stub (admin menu). */
export function MenuPage({ variant }: MenuPageProps) {
  const user = useUser();
  const { signOff } = useSession();
  const features = useFeatures();
  const navigate = useNavigate();
  const options = variant === 'main' ? MAIN_OPTIONS : ADMIN_OPTIONS;
  const [typed, setTyped] = useState('');
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const optionField = useRef<HTMLInputElement>(null);
  const messageRef = useRef<HTMLDivElement>(null);

  useEffect(() => optionField.current?.focus(), []);

  const statusOf = (option: MenuOption): OptionStatus => {
    if (option.to) return 'available';
    if (option.module === 'authorization' && !features?.modules.authorization) return 'not-installed';
    return 'coming-soon';
  };

  const show = (next: ScreenMessage) => {
    setScreenMessage(next);
    window.requestAnimationFrame(() => messageRef.current?.scrollIntoView?.({ block: 'nearest' }));
  };

  const choose = (option: MenuOption) => {
    const status = statusOf(option);
    if (status === 'available' && option.to) {
      void navigate(option.to);
    } else if (status === 'not-installed') {
      show(message('MENU_NOT_INSTALLED', { name: option.name }));
    } else {
      show(message('MENU_COMING_SOON', { name: option.name }));
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const number = parseMenuOption(typed, options.length);
    const option = number === null ? undefined : options[number - 1];
    if (!option) {
      show(message('MENU_INVALID_OPTION'));
      optionField.current?.focus();
      return;
    }
    choose(option);
  };

  const back = () => void signOff({ to: 'sign-on' });
  usePageKeys({ back, invalidKey: () => show(message('INVALID_KEY')) });

  const greeting = user.firstName.trim() ? `Welcome, ${displayName(user.firstName)}.` : 'Welcome.';
  const title = variant === 'main' ? 'Main menu' : 'Admin menu';

  return (
    <>
      <PageTitle
        title={title}
        description={`${greeting} Choose an option below, or type its number.`}
        actions={
          <Button variant="default" onClick={back}>
            Sign out
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form onSubmit={submit} className={classes.optionForm} noValidate>
        <TextInput
          ref={optionField}
          label="Option number"
          value={typed}
          onChange={(event) => setTyped(event.currentTarget.value)}
          maxLength={2}
          inputMode="numeric"
          autoComplete="off"
          className={classes.optionInput}
          classNames={{ input: 'cd-mono' }}
          aria-describedby="menu-message"
          data-testid="option-field"
        />
        <Button type="submit" className={classes.go}>
          Go
        </Button>
      </form>
      <MessageArea id="menu-message" ref={messageRef} message={screenMessage} />
      <ul className={classes.grid} aria-label={`${title} options`}>
        {options.map((option) => {
          const status = statusOf(option);
          return (
            <li key={option.number}>
              <button type="button" className={classes.tile} data-status={status} onClick={() => choose(option)} data-testid={`option-${two(option.number)}`}>
                <span className={classes.line}>
                  <span className={classes.number}>{two(option.number)}.</span> <span className={classes.name}>{option.name}</span>
                </span>
                <Badge
                  size="sm"
                  radius="sm"
                  variant={status === 'available' ? 'filled' : 'light'}
                  color={status === 'available' ? 'brand' : status === 'not-installed' ? 'red' : 'slate'}
                  className={classes.badge}
                >
                  {STATUS_LABEL[status]}
                </Badge>
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}
