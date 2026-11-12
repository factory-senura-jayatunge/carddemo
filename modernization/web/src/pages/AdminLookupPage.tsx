import { Badge, Button } from '@mantine/core';
import { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { openedFrom } from '@/lib/useBack';
import { message, type ScreenMessage } from '@/lib/messages';
import classes from './MenuPage.module.css';

interface LookupLink {
  name: string;
  description: string;
  /** Route of a converted page; the others are not built yet. */
  to?: string;
  testId: string;
}

export const LOOKUP_PATH = '/admin/lookup';

/** The read-only pages an ADMIN reaches through admin option 7 (D-09; web.md, Routes). */
export const LOOKUP_LINKS: LookupLink[] = [
  { name: 'Account View', description: 'An account with its customer and card', to: '/accounts/view', testId: 'lookup-account' },
  { name: 'Credit Card List', description: 'Cards, filtered by account or card number', to: '/cards', testId: 'lookup-card-list' },
  { name: 'Credit Card View', description: 'One card by account and card number', to: '/cards/lookup', testId: 'lookup-card' },
  { name: 'Transaction List', description: 'Every transaction, ten at a time, from an ID on', to: '/transactions', testId: 'lookup-transaction-list' },
  { name: 'Transaction View', description: 'One transaction by ID', to: '/transactions/lookup', testId: 'lookup-transaction' },
];

/** Admin option 7: links to the read-only account, card and transaction pages. */
export function AdminLookupPage() {
  const navigate = useNavigate();
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const messageRef = useRef<HTMLDivElement>(null);

  const show = (next: ScreenMessage) => {
    setScreenMessage(next);
    window.requestAnimationFrame(() => messageRef.current?.scrollIntoView?.({ block: 'nearest' }));
  };

  const back = () => void navigate('/admin');
  usePageKeys({ back, escapeBack: true, invalidKey: () => show(message('INVALID_KEY')) });

  return (
    <>
      <PageTitle
        title="Account and card view"
        description="Read-only views for an administrator. Masked values have a Show control, and every value you show is audited."
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <MessageArea id="lookup-message" ref={messageRef} message={screenMessage} />
      <ul className={classes.grid} aria-label="Read-only views">
        {LOOKUP_LINKS.map((link) => {
          const body = (
            <>
              <span className={classes.stack}>
                <span className={classes.name}>{link.name}</span>
                <span className={classes.description}>{link.description}</span>
              </span>
              <Badge
                size="sm"
                radius="sm"
                variant={link.to ? 'filled' : 'light'}
                color={link.to ? 'brand' : 'slate'}
                className={classes.badge}
              >
                {link.to ? 'Open' : 'Coming soon'}
              </Badge>
            </>
          );
          return (
            <li key={link.testId}>
              {link.to ? (
                <Link to={link.to} state={openedFrom(LOOKUP_PATH)} className={classes.tile} data-status="available" data-testid={link.testId}>
                  {body}
                </Link>
              ) : (
                <button
                  type="button"
                  className={classes.tile}
                  data-status="coming-soon"
                  data-testid={link.testId}
                  onClick={() => show(message('OPTION_COMING_SOON', { name: link.name }))}
                  aria-describedby="lookup-message"
                >
                  {body}
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </>
  );
}
