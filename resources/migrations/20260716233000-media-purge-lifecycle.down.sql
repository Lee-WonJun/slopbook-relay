DROP INDEX IF EXISTS media_purge_pending_idx;
--;;
DROP INDEX IF EXISTS media_expiry_idx;
--;;
ALTER TABLE media
  DROP COLUMN IF EXISTS last_purge_error_code,
  DROP COLUMN IF EXISTS purged_at,
  DROP COLUMN IF EXISTS purge_available_at,
  DROP COLUMN IF EXISTS purge_attempt_count;
