import { Outlet, type RouteObject } from 'react-router';
import { KeyboardShortcutsProvider } from '@/keys/KeyboardShortcuts';
import { Frame, type RouteHandle } from '@/layout/Frame';
import { BillPayPage } from '@/pages/BillPayPage';
import { ChangePasswordPage } from '@/pages/ChangePasswordPage';
import { MenuPage } from '@/pages/MenuPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { SignedOffPage } from '@/pages/SignedOffPage';
import { SignOnPage } from '@/pages/SignOnPage';
import { FunctionKeysProvider } from '@/prefs/FunctionKeys';
import { RequireRole } from '@/session/RequireRole';
import { SessionProvider } from '@/session/SessionProvider';

function Root() {
  return (
    <SessionProvider>
      <FunctionKeysProvider>
        <KeyboardShortcutsProvider>
          <Outlet />
        </KeyboardShortcutsProvider>
      </FunctionKeysProvider>
    </SessionProvider>
  );
}

const screen = (tran: string, program: string): RouteHandle => ({ screen: { tran, program } });
const SIGN_ON = screen('CC00', 'COSGN00C');

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
