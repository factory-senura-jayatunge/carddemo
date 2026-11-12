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
import type { UserListState } from './UserParts';

type OptionStatus = 'available' | 'coming-soon' | 'not-installed';

export interface MenuOption {
  number: number;
  name: string;
  /** Route of a converted option. */
  to?: string;
  /** Route state the option opens its page with. */
  state?: unknown;
  /** The optional module the option belongs to. */
  module?: 'authorization';
}

/** COMEN02Y, in order (BR-MENU-001). */
export const MAIN_OPTIONS: MenuOption[] = [
  { number: 1, name: 'Account View', to: '/accounts/view' },
  { number: 2, name: 'Account Update', to: '/accounts/edit' },
  { number: 3, name: 'Credit Card List', to: '/cards' },
  { number: 4, name: 'Credit Card View', to: '/cards/lookup' },
  { number: 5, name: 'Credit Card Update', to: '/cards/edit' },
  { number: 6, name: 'Transaction List', to: '/transactions' },
  { number: 7, name: 'Transaction View', to: '/transactions/lookup' },
  { number: 8, name: 'Transaction Add', to: '/transactions/new' },
  { number: 9, name: 'Transaction Reports', to: '/reports/transactions' },
  { number: 10, name: 'Bill Payment', to: '/bill-pay' },
  { number: 11, name: 'Pending Authorization View', to: '/authorizations', module: 'authorization' },
];

/**
 * COADM02Y options 1 to 4; 5 and 6 belong to a module that is not installed
 * and are hidden (D-70). Options 3 and 4 open the user list, where the user is
 * chosen. Option 7 is the read-only account and card lookup that D-09 gives an ADMIN.
 */
export const ADMIN_OPTIONS: MenuOption[] = [
  { number: 1, name: 'User List (Security)', to: '/admin/users' },
  { number: 2, name: 'User Add (Security)', to: '/admin/users/new' },
  { number: 3, name: 'User Update (Security)', to: '/admin/users', state: { intent: 'update' } satisfies UserListState },
  { number: 4, name: 'User Delete (Security)', to: '/admin/users', state: { intent: 'delete' } satisfies UserListState },
  { number: 7, name: 'Account and Card View', to: '/admin/lookup' },
];

const STATUS_LABEL: Record<OptionStatus, string> = {
  available: 'Open',
  'coming-soon': 'Coming soon',
  'not-installed': 'Not installed',
};

const two = (n: number) => String(n).padStart(2, '0');

interface MenuPageProps {
  variant: 'main' | 'admin';
  /** The variant's list unless given; an option without a route is coming soon (BR-MENU-005). */
  options?: MenuOption[];
}

/** COMEN01C (main menu) and the COADM01C stub (admin menu). */
export function MenuPage({ variant, options: given }: MenuPageProps) {
  const user = useUser();
  const { signOff } = useSession();
  const features = useFeatures();
  const navigate = useNavigate();
  const options = given ?? (variant === 'main' ? MAIN_OPTIONS : ADMIN_OPTIONS);
  const [typed, setTyped] = useState('');
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const optionField = useRef<HTMLInputElement>(null);
  const messageRef = useRef<HTMLDivElement>(null);

  useEffect(() => optionField.current?.focus(), []);

  const statusOf = (option: MenuOption): OptionStatus => {
    if (option.module === 'authorization' && !features?.modules.authorization) return 'not-installed';
    return option.to ? 'available' : 'coming-soon';
  };

  const show = (next: ScreenMessage) => {
    setScreenMessage(next);
    window.requestAnimationFrame(() => messageRef.current?.scrollIntoView?.({ block: 'nearest' }));
  };

  const choose = (option: MenuOption) => {
    const status = statusOf(option);
    if (status === 'available' && option.to) {
      void navigate(option.to, { state: option.state });
    } else if (status === 'not-installed') {
      show(message('OPTION_NOT_INSTALLED', { name: option.name }));
    } else {
      show(message('OPTION_COMING_SOON', { name: option.name }));
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const number = parseMenuOption(typed, Math.max(...options.map((o) => o.number)));
    const option = number === null ? undefined : options.find((o) => o.number === number);
    if (!option) {
      show(message('INVALID_OPTION'));
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
