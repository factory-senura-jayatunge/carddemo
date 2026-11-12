import { Button, Group, Modal, Text } from '@mantine/core';

const formatRemaining = (ms: number) => {
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
};

/** The idle warning itself, apart from IdleGuard so that its dialog code stays out of the first download. */
export function IdleWarningDialog({ remaining, onStay, onSignOut }: { remaining: number | null; onStay: () => void; onSignOut: () => void }) {
  return (
    <Modal
      opened={remaining !== null}
      onClose={onStay}
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
        <Button variant="default" onClick={onSignOut}>
          Sign out now
        </Button>
        <Button onClick={onStay} data-autofocus>
          Stay signed on
        </Button>
      </Group>
    </Modal>
  );
}
