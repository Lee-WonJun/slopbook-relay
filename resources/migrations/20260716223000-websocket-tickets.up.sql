CREATE TABLE websocket_tickets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  token_digest bytea NOT NULL UNIQUE CHECK (octet_length(token_digest) = 32),
  character_id uuid NOT NULL REFERENCES characters(id),
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE INDEX websocket_tickets_expiry_idx
  ON websocket_tickets (expires_at) WHERE consumed_at IS NULL;
