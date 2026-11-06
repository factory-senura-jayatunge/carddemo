import { Button, Text, ThemeIcon } from '@mantine/core';
import { Link, useLocation } from 'react-router';
import { PageTitle } from '@/components/PageTitle';
import { messages } from '@/messages';
import type { SignedOffRouteState } from '@/session/SessionProvider';
import classes from './SignOnPage.module.css';

/** After Exit or Sign out (FLOW-SGN-003): the thank-you message and a way back in. */
export function SignedOffPage() {
  const location = useLocation();
  const text = (location.state as SignedOffRouteState | null)?.message ?? messages.SIGNED_OFF.text;
  return (
    <div className={classes.narrow}>
      <ThemeIcon size={44} radius="xl" variant="light" color="brand" mb="lg" aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 20 20" fill="none">
          <path d="M5.5 10.4l3 3L14.5 7" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </ThemeIcon>
      <PageTitle title="You are signed out" />
      <Text size="lg" mb="xl" className="cd-legacy-text" data-testid="message-text">
        {text}
      </Text>
      <Button component={Link} to="/sign-on" size="md">
        Sign on again
      </Button>
    </div>
  );
}
