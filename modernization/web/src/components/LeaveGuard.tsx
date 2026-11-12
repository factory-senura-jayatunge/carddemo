import { Button, Group, Modal, Text } from '@mantine/core';
import { useEffect } from 'react';
import { useBlocker } from 'react-router';

/**
 * Asks before leaving a form with unsaved changes (D-68): in-app navigation
 * through a dialog, a reload or a closed tab through the browser's own prompt.
 */
export function LeaveGuard({ dirty }: { dirty: boolean }) {
  // The session ending (idle timeout, sign-off) is never held up.
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty && currentLocation.pathname !== nextLocation.pathname && !/^\/sign(ed)?-o/.test(nextLocation.pathname),
  );

  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  return (
    <Modal
      opened={blocker.state === 'blocked'}
      onClose={() => blocker.reset?.()}
      title="Leave without saving?"
      centered
      data-testid="leave-dialog"
    >
      <Text size="sm">Your changes have not been saved. If you leave now, they are lost.</Text>
      <Group justify="flex-end" mt="lg" gap="sm">
        <Button variant="default" onClick={() => blocker.reset?.()} data-autofocus data-testid="stay">
          Keep editing
        </Button>
        <Button color="red" onClick={() => blocker.proceed?.()} data-testid="leave">
          Leave without saving
        </Button>
      </Group>
    </Modal>
  );
}
