/**
 * The current password typed on the sign-on page, kept in memory only while
 * the user moves to the forced password change. It never goes into history
 * state or storage. The user ID is kept exactly as typed: the change page gets
 * the same raw value in its route state and sends it on unchanged, so the
 * service, not the browser, decides what padding and case mean.
 */
let pending: { userId: string; password: string } | null = null;

export function holdPasswordForChange(userId: string, password: string): void {
  pending = { userId, password };
}

export function peekPasswordForChange(userId: string): string {
  return pending && pending.userId === userId ? pending.password : '';
}

export function forgetPasswordForChange(): void {
  pending = null;
}
