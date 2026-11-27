CREATE TABLE reservations (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    charger_id bigint NOT NULL REFERENCES chargers (id),
    owner_id uuid NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    CONSTRAINT reservations_valid_duration CHECK (
        ends_at - starts_at BETWEEN INTERVAL '1 hour' AND INTERVAL '6 hours'
        AND mod(EXTRACT(EPOCH FROM (ends_at - starts_at)), 3600) = 0
    )
);

CREATE INDEX reservations_charger_start_idx ON reservations (charger_id, starts_at);
CREATE INDEX reservations_owner_start_idx ON reservations (owner_id, starts_at);
