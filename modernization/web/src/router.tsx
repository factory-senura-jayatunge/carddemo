import { lazy, Suspense, type ComponentType, type ReactNode } from 'react';
import { Outlet, type RouteObject } from 'react-router';
import { FullPageLoader } from '@/components/FullPageLoader';
import { UnexpectedErrorBoundary } from '@/components/UnexpectedError';
import { KeyboardShortcutsProvider } from '@/keys/KeyboardShortcuts';
import { Frame, type RouteHandle } from '@/layout/Frame';
import { LatestLocationOnly } from '@/lib/LatestLocationOnly';
import { AdminLookupPage } from '@/pages/AdminLookupPage';
import { BillPayPage } from '@/pages/BillPayPage';
import { ChangePasswordPage } from '@/pages/ChangePasswordPage';
import { MenuPage } from '@/pages/MenuPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { SignedOffPage } from '@/pages/SignedOffPage';
import { SignOnPage } from '@/pages/SignOnPage';
import { FunctionKeysProvider } from '@/prefs/FunctionKeys';
import { RequireModule } from '@/session/RequireModule';
import { RequireRole } from '@/session/RequireRole';
import { SessionProvider } from '@/session/SessionProvider';

function Root() {
  return (
    <SessionProvider>
      <FunctionKeysProvider>
        <KeyboardShortcutsProvider>
          <Outlet />
          <LatestLocationOnly />
        </KeyboardShortcutsProvider>
      </FunctionKeysProvider>
    </SessionProvider>
  );
}

// The account, card and user pages, with the shared rule table, load on first use so sign-on stays inside the initial budget.
const later = <K extends string>(load: () => Promise<Record<K, ComponentType>>, name: K) =>
  lazy(async () => ({ default: (await load())[name] }));
const AccountViewPage = later(() => import('@/pages/AccountViewPage'), 'AccountViewPage');
const AccountUpdatePage = later(() => import('@/pages/AccountUpdatePage'), 'AccountUpdatePage');
const CardListPage = later(() => import('@/pages/CardListPage'), 'CardListPage');
const CardViewPage = later(() => import('@/pages/CardViewPage'), 'CardViewPage');
const CardUpdatePage = later(() => import('@/pages/CardUpdatePage'), 'CardUpdatePage');
const AuthorizationsPage = later(() => import('@/pages/AuthorizationsPage'), 'AuthorizationsPage');
const AuthorizationDetailPage = later(() => import('@/pages/AuthorizationDetailPage'), 'AuthorizationDetailPage');
const UserListPage = later(() => import('@/pages/UserListPage'), 'UserListPage');
const UserAddPage = later(() => import('@/pages/UserAddPage'), 'UserAddPage');
const UserEditPage = later(() => import('@/pages/UserEditPage'), 'UserEditPage');
const UserDeletePage = later(() => import('@/pages/UserDeletePage'), 'UserDeletePage');
const TransactionListPage = later(() => import('@/pages/TransactionListPage'), 'TransactionListPage');
const TransactionViewPage = later(() => import('@/pages/TransactionViewPage'), 'TransactionViewPage');
const TransactionAddPage = later(() => import('@/pages/TransactionAddPage'), 'TransactionAddPage');
const ReportRequestPage = later(() => import('@/pages/ReportRequestPage'), 'ReportRequestPage');

const loading = (page: ReactNode) => <Suspense fallback={<FullPageLoader />}>{page}</Suspense>;

const screen = (tran: string, program: string): RouteHandle => ({ screen: { tran, program } });
const SIGN_ON = screen('CC00', 'COSGN00C');
const CARD_VIEW = screen('CCDL', 'COCRDSLC');
const CARD_UPDATE = screen('CCUP', 'COCRDUPC');
const TRAN_VIEW = screen('CT01', 'COTRN01C');
const READERS = ['USER', 'ADMIN'] as const;

/** The routes of the web contract (billpay-slice, section 9). */
export const routes: RouteObject[] = [
  {
    element: <Root />,
    children: [
      {
        element: <Frame />,
        children: [
          { path: 'sign-on', element: <SignOnPage />, handle: SIGN_ON },
          { path: 'sign-on/change-password', element: <ChangePasswordPage />, handle: SIGN_ON },
          { path: 'signed-off', element: <SignedOffPage />, handle: SIGN_ON },
          {
            index: true,
            element: (
              <RequireRole allow="USER">
                <MenuPage variant="main" />
              </RequireRole>
            ),
            handle: screen('CM00', 'COMEN01C'),
          },
          {
            path: 'bill-pay',
            element: (
              <RequireRole allow="USER">
                <BillPayPage />
              </RequireRole>
            ),
            handle: screen('CB00', 'COBIL00C'),
          },
          {
            path: 'accounts/view',
            element: (
              <RequireRole allow={READERS}>
                <UnexpectedErrorBoundary>
                  {loading(<AccountViewPage />)}
                </UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CAVW', 'COACTVWC'),
          },
          {
            path: 'accounts/edit',
            element: (
              <RequireRole allow="USER">
                <UnexpectedErrorBoundary>
                  {loading(<AccountUpdatePage />)}
                </UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CAUP', 'COACTUPC'),
          },
          {
            path: 'cards',
            element: (
              <RequireRole allow={READERS}>
                {loading(<CardListPage />)}
              </RequireRole>
            ),
            handle: screen('CCLI', 'COCRDLIC'),
          },
          {
            path: 'cards/lookup',
            element: (
              <RequireRole allow={READERS}>
                <UnexpectedErrorBoundary>
                  {loading(<CardViewPage />)}
                </UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: CARD_VIEW,
          },
          {
            path: 'cards/edit',
            element: (
              <RequireRole allow="USER">
                {loading(<CardUpdatePage />)}
              </RequireRole>
            ),
            handle: CARD_UPDATE,
          },
          {
            path: 'cards/:cardId',
            element: (
              <RequireRole allow={READERS}>
                <UnexpectedErrorBoundary backTo="/cards">
                  {loading(<CardViewPage />)}
                </UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: CARD_VIEW,
          },
          {
            path: 'cards/:cardId/edit',
            element: (
              <RequireRole allow="USER">
                {loading(<CardUpdatePage />)}
              </RequireRole>
            ),
            handle: CARD_UPDATE,
          },
          {
            path: 'transactions',
            element: (
              <RequireRole allow={READERS}>
                <UnexpectedErrorBoundary>{loading(<TransactionListPage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CT00', 'COTRN00C'),
          },
          {
            path: 'transactions/lookup',
            element: (
              <RequireRole allow={READERS}>
                <UnexpectedErrorBoundary>{loading(<TransactionViewPage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: TRAN_VIEW,
          },
          {
            path: 'transactions/new',
            element: (
              <RequireRole allow="USER">
                <UnexpectedErrorBoundary>{loading(<TransactionAddPage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CT02', 'COTRN02C'),
          },
          {
            path: 'reports/transactions',
            element: (
              <RequireRole allow="USER">
                <UnexpectedErrorBoundary>{loading(<ReportRequestPage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CR00', 'CORPT00C'),
          },
          {
            path: 'transactions/:tranId',
            element: (
              <RequireRole allow={READERS}>
                <UnexpectedErrorBoundary backTo="/transactions">{loading(<TransactionViewPage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: TRAN_VIEW,
          },
          {
            path: 'authorizations',
            element: (
              <RequireRole allow="USER">
                <RequireModule module="authorization">
                  <UnexpectedErrorBoundary backTo="/">
                    {loading(<AuthorizationsPage />)}
                  </UnexpectedErrorBoundary>
                </RequireModule>
              </RequireRole>
            ),
            handle: screen('CPVS', 'COPAUS0C'),
          },
          {
            path: 'authorizations/:authId',
            element: (
              <RequireRole allow="USER">
                <RequireModule module="authorization">
                  <UnexpectedErrorBoundary backTo="/authorizations">
                    {loading(<AuthorizationDetailPage />)}
                  </UnexpectedErrorBoundary>
                </RequireModule>
              </RequireRole>
            ),
            handle: screen('CPVD', 'COPAUS1C'),
          },
          {
            path: 'admin/lookup',
            element: (
              <RequireRole allow="ADMIN">
                <AdminLookupPage />
              </RequireRole>
            ),
            handle: screen('CA00', 'COADM01C'),
          },
          {
            path: 'admin/users',
            element: (
              <RequireRole allow="ADMIN">
                <UnexpectedErrorBoundary backTo="/admin">{loading(<UserListPage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CU00', 'COUSR00C'),
          },
          {
            path: 'admin/users/new',
            element: (
              <RequireRole allow="ADMIN">
                <UnexpectedErrorBoundary backTo="/admin">{loading(<UserAddPage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CU01', 'COUSR01C'),
          },
          {
            path: 'admin/users/:userKey/edit',
            element: (
              <RequireRole allow="ADMIN">
                <UnexpectedErrorBoundary backTo="/admin/users">{loading(<UserEditPage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CU02', 'COUSR02C'),
          },
          {
            path: 'admin/users/:userKey/delete',
            element: (
              <RequireRole allow="ADMIN">
                <UnexpectedErrorBoundary backTo="/admin/users">{loading(<UserDeletePage />)}</UnexpectedErrorBoundary>
              </RequireRole>
            ),
            handle: screen('CU03', 'COUSR03C'),
          },
          {
            path: 'admin',
            element: (
              <RequireRole allow="ADMIN">
                <MenuPage variant="admin" />
              </RequireRole>
            ),
            handle: screen('CA00', 'COADM01C'),
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];
