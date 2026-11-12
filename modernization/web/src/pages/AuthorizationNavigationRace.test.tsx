import { screen, waitFor, within } from '@testing-library/react';
import { useLayoutEffect } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type * as DetailModule from '@/pages/AuthorizationDetailPage';
import { renderApp } from '@/test/renderApp';

/**
 * The detail page's chunk arrives while the user is already going back to the list. The probe keeps
 * the chunk pending, then holds the first detail render on the CPU past React's time slice, so React
 * yields in the middle of that render, and the test navigates back to the list in that gap.
 */
const probe = vi.hoisted(() => {
  let release = () => {};
  const chunk = new Promise<void>((resolve) => {
    release = resolve;
  });
  return {
    chunk,
    release: () => release(),
    hold: null as null | (() => void),
    backIssued: false,
    mountsAfterBack: 0,
  };
});

vi.mock('@/pages/AuthorizationDetailPage', async (importOriginal) => {
  const real = await importOriginal<typeof DetailModule>();
  await probe.chunk;
  function AuthorizationDetailPage() {
    const hold = probe.hold;
    probe.hold = null;
    hold?.();
    useLayoutEffect(() => {
      if (probe.backIssued) probe.mountsAfterBack += 1;
    }, []);
    return <real.AuthorizationDetailPage />;
  }
  return { AuthorizationDetailPage };
});

const rowIds = () => screen.queryAllByTestId(/^row-tran-\d$/).map((cell) => cell.textContent);
const tran = (authId: number) => `TRN${String(authId).padStart(12, '0')}`;

describe('a navigation that supersedes a detail render still in progress', () => {
  it('BR-AUSM-007 BR-AUSM-008: back to the list keeps the list, and F3 acts on the list', async () => {
    const app = await renderApp('/authorizations', { signedOnAs: 'USER0001', functionKeys: true, settings: { authorizationInstalled: true } });
    await screen.findByRole('heading', { name: 'Pending authorizations' });
    const acct = screen.getByTestId('acct-id');
    await app.user.clear(acct);
    await app.user.type(acct, '00000000001{Enter}');
    await waitFor(() => expect(rowIds()).toHaveLength(5));
    const listHeading = screen.getByRole('heading', { name: 'Pending authorizations' });

    await app.user.click(within(screen.getByTestId('auth-row-2')).getByRole('link', { name: `View authorization ${tran(1011)}` }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/authorizations/1011'));
    expect(listHeading).toBeInTheDocument();

    const rendering = new Promise<void>((resolve) => {
      probe.hold = () => {
        resolve();
        const until = performance.now() + 60;
        while (performance.now() < until) {
          // Busy on purpose: React only yields between units of work once its slice is used up.
        }
      };
    });
    probe.release();
    await rendering;
    probe.backIssued = true;
    await app.router.navigate('/authorizations');
    await screen.findByRole('heading', { name: 'Pending authorizations' });
    await waitFor(() => expect(rowIds()).toHaveLength(5));

    expect(probe.mountsAfterBack).toBe(0);
    expect(screen.getByRole('heading', { name: 'Pending authorizations' })).toBe(listHeading);
    expect(screen.queryByRole('heading', { name: 'Authorization details' })).toBeNull();
    await app.user.keyboard('{F3}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });
});
