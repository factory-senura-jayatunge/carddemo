/**
 * The ledger primitives every writer of transactions and balances shares: ID allocation, the
 * transaction insert, posting and the batch-window guard. Services call these interfaces; no
 * other code writes {@code core.transaction} or changes a balance.
 */
package org.carddemo.platform.ledger;
