ALTER TABLE payment_attempts
    ADD COLUMN next_reconcile_at TIMESTAMPTZ NOT NULL
                                                   DEFAULT (now() + INTERVAL '2 minutes'),
    ADD COLUMN reconcile_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (reconcile_attempts >= 0);

CREATE INDEX payment_attempts_reconcile_due_idx
    ON payment_attempts (next_reconcile_at, created_at)
    WHERE status IN ('CREATING', 'PENDING');