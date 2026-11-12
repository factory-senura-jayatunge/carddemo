import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { sessionEndedNote } from '@/lib/messages';
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

// Rendered from the start, so its code is fetched right after sign-on and is ready long before a warning.
const IdleWarningDialog = lazy(async () => ({ default: (await import('./IdleWarningDialog')).IdleWarningDialog }));

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
        void signOff({ to: 'sign-on', note: sessionEndedNote });
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
    if (!(await extend())) void signOff({ to: 'sign-on', note: sessionEndedNote });
  };

  return (
    <Suspense fallback={null}>
      <IdleWarningDialog remaining={remaining} onStay={() => void stay()} onSignOut={() => void signOff({ to: 'signed-off' })} />
    </Suspense>
  );
}
