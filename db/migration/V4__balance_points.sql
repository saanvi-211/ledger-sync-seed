-- The balances the banks state alongside transactions. Reconciliation uses
-- these to watch the account balance tick down while reading the messages, and
-- to spot movements that no message accounts for.
CREATE TABLE IF NOT EXISTS balance_points (
    account_last4   VARCHAR(4)     NOT NULL,
    as_of           VARCHAR(40)    NOT NULL,
    stated_balance  DECIMAL(14, 2) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_balance_point
    ON balance_points (account_last4, as_of, stated_balance);