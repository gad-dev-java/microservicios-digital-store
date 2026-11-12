ALTER TABLE payment_attempts
    ADD COLUMN resolution_reason VARCHAR(1000);

ALTER TABLE payment_attempts
    ADD CONSTRAINT payment_attempts_resolution_reason_not_blank
        CHECK (
            resolution_reason IS NULL
                OR length(btrim(resolution_reason)) > 0
            );