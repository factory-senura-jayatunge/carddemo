import { Button, Text } from '@mantine/core';
import { Link } from 'react-router';
import { PageTitle } from '@/components/PageTitle';
import { useSession } from '@/session/SessionProvider';

export function NotFoundPage() {
  const { state } = useSession();
  const home = state.status === 'authenticated' ? state.user.home : '/sign-on';
  return (
    <div>
      <PageTitle title="Page not found" />
      <Text mb="lg">There is no page at this address.</Text>
      <Button component={Link} to={home} variant="default">
        Go to the menu
      </Button>
    </div>
  );
}
