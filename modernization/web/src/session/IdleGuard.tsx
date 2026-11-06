import { Button, Group, Modal, Text } from '@mantine/core';
import { useCallback, useEffect, useRef, useState } from 'react';
import { uiText } from '@/lib/messages';
import { useSession, useUser } from './SessionProvider';

export interface IdleTimings {
  /** Sign off after this much time without user input (D-05: 15 minutes). */
  timeoutMs: number;
  /** How long before that the warning appears (WCAG 2.2.1 asks for at least 20 s). */
  warnBeforeMs: number;
}

function mockOverride(): Partial<IdleTimings> {
  if (import.meta.env.MODE !== 'mock') return {};
  try {
    return JSON.parse(sessionStorage.getItem('carddemo.mock.idle') ?? '{}') as Partial<IdleTimings>;
  } catch {
    return {};
  }
}

const formatRemaining = (ms: number) => {
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
};

/**
 * Warns two minutes before the idle timeout and signs the user off when it
 * passes. Input is any key press, pointer press or scroll. While the warning
 * is open only its buttons count, so it is never dismissed by accident.
 */
export function IdleGuard({ timings }: { timings?: IdleTimings }) {
  const { signOff, extend } = useSession();
  const user = useUser();
  const idle: IdleTimings = timings ?? {
    timeoutMs: user.idleTimeoutSeconds * 1000,
    warnBeforeMs: 120_000,
    ...mockOverride(),
  };
  const { timeoutMs, warnBeforeMs } = idle;
  const lastInput = useRef(0);
  const [remaining, setRemaining] = useState<number | null>(null);
  const warning = useRef(false);

  const touch = useCallback(() => {
    if (!warning.current) lastInput.current = Date.now();
  }, []);

  useEffect(() => {
    lastInput.current = Date.now();
    const events = ['keydown', 'pointerdown', 'wheel', 'touchstart'] as const;
    events.forEach((name) => window.addEventListener(name, touch, { passive: true }));
    return () => events.forEach((name) => window.removeEventListener(name, touch));
  }, [touch]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      const left = timeoutMs - (Date.now() - lastInput.current);
      if (left <= 0) {
        window.clearInterval(timer);
        warning.current = false;
        setRemaining(null);
        void signOff({ to: 'sign-on', note: { text: uiText.idleSignedOut } });
      } else if (left <= warnBeforeMs) {
        warning.current = true;
        setRemaining(left);
      }
    }, 1000);
    return () => window.clearInterval(timer);
  }, [timeoutMs, warnBeforeMs, signOff]);

  const stay = async () => {
    warning.current = false;
    lastInput.current = Date.now();
    setRemaining(null);
    if (!(await extend())) void signOff({ to: 'sign-on', note: { text: uiText.sessionExpired } });
  };

  return (
    <Modal
      opened={remaining !== null}
      onClose={() => void stay()}
      title="Are you still there?"
      centered
      radius="lg"
      closeOnClickOutside={false}
      closeButtonProps={{ 'aria-label': 'Close' }}
    >
      <Text size="sm" mb="lg">
        For your security you will be signed off in{' '}
        <Text span fw={650} className="cd-num">
          {formatRemaining(remaining ?? 0)}
        </Text>{' '}
        because there has been no activity for a while.
      </Text>
      <Group justify="flex-end" gap="sm">
        <Button variant="default" onClick={() => void signOff({ to: 'signed-off' })}>
          Sign out now
        </Button>
        <Button onClick={() => void stay()} data-autofocus>
          Stay signed on
        </Button>
      </Group>
    </Modal>
  );
}
