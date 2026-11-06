import { Avatar, Badge, Group, Menu, Switch, Text, UnstyledButton, VisuallyHidden } from '@mantine/core';
import { useEffect, useState } from 'react';
import { Link, Outlet, useMatches } from 'react-router';
import type { Role } from '@/api/client';
import { BrandMark } from '@/components/BrandMark';
import { displayName } from '@/lib/names';
import { formatHeaderDate, formatHeaderTime } from '@/lib/format';
import { useFunctionKeys } from '@/prefs/FunctionKeys';
import { IdleGuard } from '@/session/IdleGuard';
import { useSession, useUser } from '@/session/SessionProvider';
import classes from './Frame.module.css';

/** The legacy transaction and program of a route, shown in the page header. */
export interface ScreenInfo {
  tran: string;
  program: string;
}

export interface RouteHandle {
  screen?: ScreenInfo;
}

function useScreen(): ScreenInfo | undefined {
  const matches = useMatches();
  for (let i = matches.length - 1; i >= 0; i -= 1) {
    const screen = (matches[i]?.handle as RouteHandle | undefined)?.screen;
    if (screen) return screen;
  }
  return undefined;
}

/** The legacy header date and time (FLOW-BILL-009, NUM-BILL-004), updated every second. */
export function HeaderClock({ now: fixed }: { now?: Date }) {
  const [now, setNow] = useState(() => fixed ?? new Date());
  useEffect(() => {
    if (fixed) return;
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, [fixed]);
  return (
    <time className={classes.clock} dateTime={now.toISOString()} data-testid="header-clock">
      <VisuallyHidden>Date and time: </VisuallyHidden>
      <span data-testid="header-date">{formatHeaderDate(now)}</span>
      <span className={classes.clockTime} data-testid="header-time">
        {formatHeaderTime(now)}
      </span>
    </time>
  );
}

function FunctionKeySwitch() {
  const { enabled, setEnabled } = useFunctionKeys();
  return (
    <Switch
      size="sm"
      checked={enabled}
      onChange={(event) => setEnabled(event.currentTarget.checked)}
      label="F-keys"
      aria-describedby="fkeys-hint"
      classNames={{ label: classes.switchLabel }}
      data-testid="fkeys-toggle"
      description={<VisuallyHidden id="fkeys-hint">3270 function keys: F3 is Back or Exit, F4 is Clear.</VisuallyHidden>}
    />
  );
}

const roleLabel = (role: Role) => (role === 'ADMIN' ? 'Administrator' : 'User');

function UserMenu() {
  const user = useUser();
  const { signOff } = useSession();
  const name = displayName([user.firstName, user.lastName].filter(Boolean).join(' '));
  const initials = (name || user.userId)
    .split(/\s+/)
    .map((part) => part[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();
  return (
    <Menu position="bottom-end" width={240} shadow="md" withinPortal>
      <Menu.Target>
        <UnstyledButton className={classes.userButton}>
          <VisuallyHidden>Account menu: </VisuallyHidden>
          <Group gap={10} wrap="nowrap">
            <Avatar size={32} radius="xl" color="brand" variant="light" aria-hidden="true">
              {initials}
            </Avatar>
            <span className={classes.userText}>
              <Text span size="sm" fw={600} lh={1.2} display="block">
                {name || user.userId}
              </Text>
              <Text span size="xs" c="dimmed" lh={1.2} display="block">
                <span className="cd-mono">{user.userId}</span> · {roleLabel(user.role)}
              </Text>
            </span>
            <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" className={classes.chevron}>
              <path d="M3 4.5l3 3 3-3" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </Group>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>
          Signed on as <span className="cd-mono">{user.userId}</span>
        </Menu.Label>
        <Menu.Item onClick={() => void signOff({ to: 'signed-off' })}>Sign out</Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

/** The frame of every page: the header with the legacy titles, screen IDs, clock and toggles. */
export function Frame() {
  const { state } = useSession();
  const screen = useScreen();
  const signedOn = state.status === 'authenticated';
  return (
    <div className={classes.page}>
      <a href="#main" className="cd-skip-link">
        Skip to main content
      </a>
      <header className={classes.header}>
        <div className={classes.headerInner}>
          <Link to={signedOn ? state.user.home : '/sign-on'} className={classes.brand}>
            <span className={classes.mark}>
              <BrandMark />
            </span>
            <span className={classes.titles} data-testid="header-titles">
              <span className={classes.title1}>AWS Mainframe Modernization</span>
              <span className={classes.title2}>CardDemo</span>
            </span>
            <VisuallyHidden>, home</VisuallyHidden>
          </Link>
          {import.meta.env.MODE === 'mock' && (
            <Badge variant="outline" color="slate" radius="sm" size="sm" className={classes.env}>
              Demo data
            </Badge>
          )}
          <div className={classes.meta}>
            {screen && (
              <span className={classes.screenId} title="Mainframe transaction and program" data-testid="screen-id">
                <VisuallyHidden>Mainframe screen </VisuallyHidden>
                {screen.tran} · {screen.program}
              </span>
            )}
            <HeaderClock />
          </div>
          <div className={classes.tools}>
            <FunctionKeySwitch />
            {signedOn && <UserMenu />}
          </div>
        </div>
      </header>
      <main id="main" tabIndex={-1} className={classes.main}>
        <Outlet />
      </main>
      <footer className={classes.footer}>
        <Text size="xs" c="dimmed">
          CardDemo, modernized from the AWS mainframe sample application.
        </Text>
      </footer>
      {signedOn && <IdleGuard key={state.user.userId} />}
    </div>
  );
}
