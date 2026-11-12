import { cleanup, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { readCurrentAccount } from '@/session/currentAccount';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const messageTone = () => screen.queryByTestId('message')?.getAttribute('data-tone') ?? null;
const toggle = () => screen.getByTestId('fraud-toggle');

type Options = Parameters<typeof renderApp>[1];

async function openDetail(authId: number | string, options: Options = {}) {
  const app = await renderApp(`/authorizations/${authId}`, { signedOnAs: 'USER0001', ...options, settings: { authorizationInstalled: true, ...options?.settings } });
  await screen.findByRole('heading', { name: 'Authorization details' });
  return app;
}

async function shown(authId: number) {
  await waitFor(() => expect(screen.getByTestId('detail-tran')).toHaveTextContent(`TRN${String(authId).padStart(12, '0')}`));
}

const fraudPosts = (app: Awaited<ReturnType<typeof renderApp>>) => app.requests.filter((r) => r.method === 'POST' && r.url.endsWith('/fraud'));

describe('Authorization details (COPAUS1C, COPAUS2C)', () => {
  it('A10 FLOW-AUDT-002: every field, the reason text, the authorization code and the masked card', async () => {
    await openDetail(1012);
    await shown(1012);
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CPVD · COPAUS1C');
    expect(screen.getByTestId('detail-approval')).toHaveTextContent('A');
    expect(screen.getByTestId('detail-reason')).toHaveTextContent('0000-APPROVED');
    expect(screen.getByTestId('detail-approved-amount')).toHaveTextContent('$50.00');
    expect(screen.getByTestId('detail-auth-code')).toHaveTextContent('001012');
    expect(screen.getByTestId('detail-processing')).toHaveTextContent('000000');
    expect(screen.getByTestId('detail-card').textContent).toMatch(/^\*{12}\d{4}$/);
    expect(screen.getByTestId('detail-expiry')).toHaveTextContent('04/28');
    expect(screen.getByTestId('detail-match')).toHaveTextContent('P');
    expect(screen.queryByTestId('detail-matched-tran')).toBeNull();
    expect(screen.getByTestId('merchant-name')).toHaveTextContent(/\S/);
    expect(screen.getByTestId('detail-fraud-display')).toHaveTextContent('-');
    expect(toggle()).toHaveTextContent('Report fraud');
    await waitFor(() => expect(toggle()).toHaveFocus());
    expect(messageText()).toBeNull();
    expect(readCurrentAccount()?.acctId).toBe('00000000001');
  });

  it('A11 A13: an unknown reason shows 9999-ERROR; a matched row shows its transaction', async () => {
    const app = await openDetail(1011);
    await shown(1011);
    expect(screen.getByTestId('detail-reason')).toHaveTextContent('9999-ERROR');
    expect(screen.getByTestId('detail-approval')).toHaveTextContent('D');
    await app.router.navigate('/authorizations/1001');
    await shown(1001);
    expect(screen.getByTestId('detail-match')).toHaveTextContent('M');
    expect(screen.getByTestId('detail-matched-tran')).toHaveTextContent('0000000000000301');
  });

  it('BR-AUDT-006 VAL-AUDT-003: Next opens the next older one; at the oldest the web says so and keeps the detail', async () => {
    const app = await openDetail(1002, { functionKeys: true });
    await shown(1002);
    await app.user.click(screen.getByTestId('next'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/authorizations/1001'));
    await shown(1001);
    const reads = app.apiCalls('/api/authorizations/').length;
    await app.user.keyboard('{F8}');
    expect(messageText()).toBe('Already at the last Authorization...');
    expect(messageTone()).toBe('error');
    expect(app.apiCalls('/api/authorizations/')).toHaveLength(reads);
    expect(screen.getByTestId('detail-tran')).toBeInTheDocument();
  });

  it('A14 BR-AUDT-007 BR-AUDT-008: Report fraud sends F with If-Match, then Remove sends R', async () => {
    const app = await openDetail(1012);
    await shown(1012);
    await app.user.click(toggle());
    await waitFor(() => expect(messageText()).toBe('AUTH MARKED FRAUD...'));
    expect(messageTone()).toBe('info');
    expect(screen.getByTestId('detail-fraud-display').textContent).toMatch(/^F-\d{2}\/\d{2}\/\d{2}$/);
    expect(toggle()).toHaveTextContent('Remove fraud report');
    const [first] = fraudPosts(app);
    expect(first?.headers.get('If-Match')).toMatch(/^"[0-9a-f]{16}"$/);
    expect(await first?.clone().json()).toEqual({ action: 'F' });
    await app.user.click(toggle());
    await waitFor(() => expect(messageText()).toBe('AUTH FRAUD REMOVED...'));
    expect(screen.getByTestId('detail-fraud-display').textContent).toMatch(/^R-/);
    const second = fraudPosts(app)[1];
    expect(await second?.clone().json()).toEqual({ action: 'R' });
    expect(second?.headers.get('If-Match')).not.toBe(first?.headers.get('If-Match'));
  });

  it('F5 toggles the fraud flag with the function keys on', async () => {
    const app = await openDetail(1012, { functionKeys: true });
    await shown(1012);
    await app.user.keyboard('{F5}');
    await waitFor(() => expect(messageText()).toBe('AUTH MARKED FRAUD...'));
  });

  it('A15 A24: a change since the view gives RECORD_CHANGED and shows the stored detail to decide again', async () => {
    const app = await openDetail(1012);
    await shown(1012);
    // Someone else marks it in the meantime.
    const detail = (await (await app.server.handle(new Request(`${window.location.origin}/api/authorizations/1012`))).json()) as { version: string };
    const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
    const other = await app.server.handle(
      new Request(`${window.location.origin}/api/authorizations/1012/fraud`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, 'If-Match': `"${detail.version}"` },
        body: JSON.stringify({ action: 'F' }),
      }),
    );
    expect(other.status).toBe(200);
    expect(toggle()).toHaveTextContent('Report fraud');
    await app.user.click(toggle());
    await waitFor(() => expect(messageText()).toBe('Record changed by some one else. Please review'));
    expect(screen.getByTestId('detail-fraud-display').textContent).toMatch(/^F-/);
    expect(toggle()).toHaveTextContent('Remove fraud report');
    await app.user.click(toggle());
    await waitFor(() => expect(messageText()).toBe('AUTH FRAUD REMOVED...'));
  });

  it('A17 DEF-AUDT-003: a failed fraud write keeps the stored status with the rollback text', async () => {
    const app = await openDetail(1012);
    await shown(1012);
    app.settings.faults = ['fraud-update'];
    await app.user.click(toggle());
    await waitFor(() => expect(messageText()).toBe(' System error while FRAUD Tagging, ROLLBACK'));
    expect(screen.getByTestId('detail-fraud-display')).toHaveTextContent('-');
    expect(toggle()).toHaveTextContent('Report fraud');
  });

  it('A18 A23 VAL-AUDT-001: not found, an invalid ID and failed reads show their text and no detail', async () => {
    const cases: [string, string[], string][] = [
      ['77', [], 'Authorization not found...'],
      ['abc', [], 'Authorization ID must be a positive number'],
      ['1012', ['auth-detail-read'], ' System error while reading Auth Details'],
      ['1012', ['auth-next-read'], ' System error while reading next Auth'],
    ];
    for (const [authId, faults, text] of cases) {
      const app = await openDetail(authId, { settings: { faults } });
      await waitFor(() => expect(messageText()).toBe(text));
      expect(screen.queryByTestId('auth-detail')).toBeNull();
      await waitFor(() => expect(screen.getByRole('button', { name: /^Back/ })).toHaveFocus());
      app.router.dispose();
      cleanup();
    }
  });

  it('BR-AUDT-005: Back returns to the summary, which shows the current account at once', async () => {
    const app = await openDetail(2003);
    await shown(2003);
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/authorizations'));
    await waitFor(() => expect(screen.getByTestId('acct-id')).toHaveValue('00000000002'));
    await waitFor(() => expect(screen.getAllByTestId(/^row-tran-\d$/)).toHaveLength(5));
  });
});
