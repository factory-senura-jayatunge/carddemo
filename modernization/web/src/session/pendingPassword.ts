/**
 * The current password typed on the sign-on page, kept in memory only while
 * the user moves to the forced password change. It never goes into history
 * state or storage.
 */
let pending: { userId: string; password: string } | null = null;

export function holdPasswordForChange(userId: string, password: string): void {
  pending = { userId: userId.trim().toUpperCase(), password };
}

export function peekPasswordForChange(userId: string): string {
  return pending && pending.userId === userId.trim().toUpperCase() ? pending.password : '';
}

export function forgetPasswordForChange(): void {
  pending = null;
}
