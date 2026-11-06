import { forwardRef, type ReactNode } from 'react';
import type { ScreenMessage } from '@/lib/messages';
import { severityTone } from '@/theme/tokens';
import classes from './MessageArea.module.css';

type Tone = ScreenMessage['tone'];

function Icon({ tone }: { tone: Tone }) {
  const common = { width: 20, height: 20, viewBox: '0 0 20 20', 'aria-hidden': true, focusable: false } as const;
  switch (tone) {
    case 'success':
      return (
        <svg {...common}>
          <circle cx="10" cy="10" r="9" fill="currentColor" opacity="0.14" />
          <path d="M6 10.4l2.6 2.6L14 7.6" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      );
    case 'error':
      return (
        <svg {...common}>
          <circle cx="10" cy="10" r="9" fill="currentColor" opacity="0.14" />
          <path d="M10 5.6v5.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
          <circle cx="10" cy="14.2" r="1.15" fill="currentColor" />
        </svg>
      );
    case 'notice':
      return (
        <svg {...common}>
          <circle cx="10" cy="10" r="9" fill="currentColor" opacity="0.14" />
          <path d="M10 5.5V10l3 1.8" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <circle cx="10" cy="10" r="9" fill="currentColor" opacity="0.14" />
          <circle cx="10" cy="6.2" r="1.15" fill="currentColor" />
          <path d="M10 9v5.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
        </svg>
      );
  }
}

interface MessageAreaProps {
  id: string;
  message: ScreenMessage | null;
  /** Extra content under the message, such as a retry hint. */
  children?: ReactNode;
}

/**
 * The message line of a screen: one message at a time, with the exact
 * catalogue text. The container is a permanent polite live region, so a new
 * message is announced; the tone is spelled out for screen readers and shown
 * by an icon, never by colour alone.
 */
export const MessageArea = forwardRef<HTMLDivElement, MessageAreaProps>(function MessageArea({ id, message, children }, ref) {
  const tone = message ? severityTone[message.tone] : null;
  return (
    <div ref={ref} id={id} aria-live="polite" aria-atomic="true" className={classes.region}>
      {message && tone && (
        <div className={classes.message} data-tone={message.tone} data-testid="message" style={{ color: tone.fg, background: tone.bg, borderColor: tone.border }}>
          <span className={classes.icon}>
            <Icon tone={message.tone} />
          </span>
          <div className={classes.body}>
            <p className={classes.text}>
              <span className={classes.srOnly}>{tone.label}: </span>
              <span className="cd-legacy-text" data-testid="message-text">
                {message.text}
              </span>
            </p>
            {children}
          </div>
        </div>
      )}
    </div>
  );
});
