ALTER TABLE media
  ADD COLUMN purge_attempt_count integer NOT NULL DEFAULT 0 CHECK (purge_attempt_count >= 0),
  ADD COLUMN purge_available_at timestamptz,
  ADD COLUMN purged_at timestamptz,
  ADD COLUMN last_purge_error_code text;
--;;

UPDATE media
   SET purge_available_at = now()
 WHERE status = 'deleted' AND variants IS NOT NULL AND purged_at IS NULL;
--;;

CREATE INDEX media_expiry_idx
  ON media (expires_at, id)
  WHERE expires_at IS NOT NULL AND status NOT IN ('attached', 'deleted');
--;;

CREATE INDEX media_purge_pending_idx
  ON media (purge_available_at, id)
  WHERE purge_available_at IS NOT NULL AND variants IS NOT NULL AND purged_at IS NULL;
