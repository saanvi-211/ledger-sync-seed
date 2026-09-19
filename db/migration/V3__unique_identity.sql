-- Give every transaction a stable identity: account, minute, direction,
-- amount. The store has run for years without uniqueness anywhere, so first
-- settle existing rows (merge duplicates, keeping the oldest id) and then
-- enforce the guarantee going forward. Re-running an ingest must never add a
-- second row.
ALTER TABLE ledger ADD COLUMN IF NOT EXISTS identity_key VARCHAR(70);

UPDATE ledger
SET identity_key = account_last4 || '|' || LEFT(occurred_at, 16)
    || '|' || direction || '|' || amount
WHERE identity_key IS NULL OR identity_key = '';

-- Keep the earliest row per identity. Its source_message_ids survive; the
-- later duplicates only re-stated what the earliest already said.
DELETE FROM ledger a
WHERE a.id NOT IN (SELECT MIN(id) FROM ledger GROUP BY identity_key);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_identity ON ledger (identity_key);

ALTER TABLE ledger ALTER COLUMN identity_key SET NOT NULL;