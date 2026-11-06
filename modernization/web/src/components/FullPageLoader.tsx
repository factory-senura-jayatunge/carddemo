import { Center, Loader } from '@mantine/core';

export function FullPageLoader() {
  return (
    <Center mih="60vh" role="status" aria-label="Loading">
      <Loader size="md" />
    </Center>
  );
}
