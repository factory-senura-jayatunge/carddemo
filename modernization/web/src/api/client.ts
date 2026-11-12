import createClient from 'openapi-fetch';
import type { operations as AccountOps, paths as AccountPaths } from './generated/account-service';
import type { operations as AuthorizationOps, paths as AuthorizationPaths } from './generated/authorization-service';
import type { operations as BillpayOps, paths as BillpayPaths } from './generated/billpay-service';
import type { operations as IdentityOps, paths as IdentityPaths } from './generated/identity-service';
import type { operations as TransactionOps, paths as TransactionPaths } from './generated/transaction-service';
import { apiFetch } from './http';

// openapi-fetch builds a Request from the URL; an absolute base keeps that
// working outside a browser page too (Vitest).
const options = {
  baseUrl: typeof window === 'undefined' ? '' : window.location.origin,
  fetch: apiFetch,
  credentials: 'same-origin' as const,
};

export const identityApi = createClient<IdentityPaths>(options);
export const billpayApi = createClient<BillpayPaths>(options);
export const accountApi = createClient<AccountPaths>(options);
export const authorizationApi = createClient<AuthorizationPaths>(options);
export const transactionApi = createClient<TransactionPaths>(options);

// Types by operation ID, so they do not depend on the schema names a service
// chooses in its OpenAPI export.
type JsonOf<R> = R extends { content: { 'application/json': infer B } } ? B : never;

export type SessionView = JsonOf<IdentityOps['me']['responses'][200]>;
export type Features = JsonOf<IdentityOps['features']['responses'][200]>;
export type SignOffResult = JsonOf<IdentityOps['signOff']['responses'][200]>;
export type Preview = JsonOf<BillpayOps['previewBillPayment']['responses'][200]>;
export type PaymentResult = JsonOf<BillpayOps['payBill']['responses'][201]>;
export type Role = SessionView['role'];
export type UserPage = JsonOf<IdentityOps['listUsers']['responses'][200]>;
export type ListedUser = UserPage['items'][number];
export type UserView = JsonOf<IdentityOps['getUserByKey']['responses'][200]>;
export type SavedUser = JsonOf<IdentityOps['addUser']['responses'][201]>;
export type DeletedUser = JsonOf<IdentityOps['deleteUserByKey']['responses'][200]>;
export type NewUserRequest = IdentityOps['addUser']['requestBody']['content']['application/json'];
export type UpdateUserRequest = IdentityOps['updateUserByKey']['requestBody']['content']['application/json'];
export type AccountView = JsonOf<AccountOps['viewAccount']['responses'][200]>;
export type AccountUpdateForm = JsonOf<AccountOps['fetchAccountForUpdate']['responses'][200]>;
export type AccountValidation = JsonOf<AccountOps['validateAccountUpdate']['responses'][200]>;
export type AccountUpdateResult = JsonOf<AccountOps['updateAccount']['responses'][200]>;
export type UnmaskResult = JsonOf<AccountOps['unmaskAccount']['responses'][200]>;
export type CardPage = JsonOf<AccountOps['listCards']['responses'][200]>;
export type CardRow = CardPage['items'][number];
export type CardView = JsonOf<AccountOps['viewCard']['responses'][200]>;
export type CardUpdateForm = JsonOf<AccountOps['fetchCardForUpdate']['responses'][200]>;
export type CardUpdateResult = JsonOf<AccountOps['updateCard']['responses'][200]>;
export type CardValidation = JsonOf<AccountOps['validateCardUpdate']['responses'][200]>;
export type AuthorizationSummary = JsonOf<AuthorizationOps['getAuthorizationSummary']['responses'][200]>;
export type AuthorizationPage = JsonOf<AuthorizationOps['listAuthorizations']['responses'][200]>;
export type AuthorizationRow = AuthorizationPage['items'][number];
export type AuthorizationDetail = JsonOf<AuthorizationOps['getAuthorization']['responses'][200]>;
export type FraudResult = JsonOf<AuthorizationOps['changeAuthorizationFraud']['responses'][200]>;
export type TransactionPage = JsonOf<TransactionOps['listTransactions']['responses'][200]>;
export type TransactionRow = TransactionPage['items'][number];
export type TransactionView = JsonOf<TransactionOps['viewTransaction']['responses'][200]>;
export type TransactionForm = TransactionOps['addTransaction']['requestBody']['content']['application/json'];
export type TransactionCheck = JsonOf<TransactionOps['validateTransaction']['responses'][200]>;
export type AddResult = JsonOf<TransactionOps['addTransaction']['responses'][201]>;
export type LatestTransaction = JsonOf<TransactionOps['latestTransaction']['responses'][200]>;
export type ReportRequest = TransactionOps['requestTransactionReport']['requestBody']['content']['application/json'];
export type ReportRun = JsonOf<TransactionOps['requestTransactionReport']['responses'][202]>;
export type CardChanges = NonNullable<AccountOps['validateCardUpdate']['requestBody']>['content']['application/json']['changes'];
