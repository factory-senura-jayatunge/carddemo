import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { RouterProvider, type createBrowserRouter } from 'react-router';
import { cssVariablesResolver, theme } from '@/theme/theme';

type Router = ReturnType<typeof createBrowserRouter>;

export function App({ router }: { router: Router }) {
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } }),
  );
  return (
    // env "test" turns off Mantine's transitions and portals under Vitest, so
    // menus and dialogs open in the same tick in jsdom.
    <MantineProvider
      theme={theme}
      cssVariablesResolver={cssVariablesResolver}
      defaultColorScheme="light"
      env={import.meta.env.MODE === 'test' ? 'test' : 'default'}
    >
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>
  );
}
