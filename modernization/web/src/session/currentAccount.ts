/**
 * The "current account" of web.md (Session state): the account, customer and
 * card the user last opened, as IDs only. It replaces CDEMO-ACCT-ID,
 * CDEMO-CUST-ID and CDEMO-CARD-NUM of the commarea; the card is held by its
 * cardId, never by its number. It lives in session storage and is cleared
 * when the session ends.
 */
export interface CurrentAccount {
  acctId: string;
  custId: string | null;
  cardId: string | null;
}

const KEY = 'carddemo.currentAccount';

export function readCurrentAccount(): CurrentAccount | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<CurrentAccount>;
    return typeof value.acctId === 'string' ? { acctId: value.acctId, custId: value.custId ?? null, cardId: value.cardId ?? null } : null;
  } catch {
    return null;
  }
}

export function setCurrentAccount(value: CurrentAccount): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify({ acctId: value.acctId, custId: value.custId, cardId: value.cardId }));
  } catch {
    // Storage can be unavailable; the store is a convenience, not a requirement.
  }
}

export function clearCurrentAccount(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // As above.
  }
}
