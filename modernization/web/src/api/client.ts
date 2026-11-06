import createClient from 'openapi-fetch';
import type { operations as BillpayOps, paths as BillpayPaths } from './generated/billpay-service';
import type { operations as IdentityOps, paths as IdentityPaths } from './generated/identity-service';
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

// Types by operation ID, so they do not depend on the schema names a service
// chooses in its OpenAPI export.
type JsonOf<R> = R extends { content: { 'application/json': infer B } } ? B : never;

export type SessionView = JsonOf<IdentityOps['me']['responses'][200]>;
export type Features = JsonOf<IdentityOps['features']['responses'][200]>;
export type SignOffResult = JsonOf<IdentityOps['signOff']['responses'][200]>;
export type Preview = JsonOf<BillpayOps['previewBillPayment']['responses'][200]>;
export type PaymentResult = JsonOf<BillpayOps['payBill']['responses'][201]>;
export type Role = SessionView['role'];
