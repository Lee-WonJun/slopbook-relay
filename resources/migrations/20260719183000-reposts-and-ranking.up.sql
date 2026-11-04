CREATE TABLE post_reposts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  target_id uuid NOT NULL REFERENCES posts(id),
  character_id uuid NOT NULL REFERENCES characters(id),
  persona_snapshot jsonb NOT NULL,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT post_reposts_unique_actor UNIQUE (target_id, character_id),
  CONSTRAINT post_reposts_public_snapshot CHECK (
    jsonb_typeof(persona_snapshot) = 'object'
    AND NOT (persona_snapshot ? 'persona_behavior'))
);
--;;

CREATE INDEX post_reposts_active_target_idx
  ON post_reposts (target_id, created_at DESC, id DESC) WHERE active;
--;;

CREATE INDEX post_reposts_character_idx
  ON post_reposts (character_id, created_at DESC, id DESC);
--;;

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
--;;

ALTER TABLE notifications
  ADD CONSTRAINT notifications_type_check
  CHECK (type IN ('comment', 'reply', 'reaction', 'repost', 'follow', 'dm'));
