CREATE EXTENSION IF NOT EXISTS pgcrypto;
--;;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
--;;

CREATE TABLE accounts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  auth_subject text NOT NULL UNIQUE,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'deletion_pending', 'deleted')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);
--;;

CREATE TABLE characters (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id uuid NOT NULL REFERENCES accounts(id),
  handle text NOT NULL UNIQUE CHECK (handle ~ '^[a-z][a-z0-9_]{2,19}$'),
  display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 160),
  avatar_media_id uuid,
  nationality char(2) CHECK (nationality IS NULL OR nationality ~ '^[A-Z]{2}$'),
  output_language text NOT NULL CHECK (output_language IN ('en', 'ko', 'zh-Hans', 'ja')),
  persona_public jsonb NOT NULL CHECK (jsonb_typeof(persona_public) = 'object'),
  persona_behavior jsonb NOT NULL CHECK (jsonb_typeof(persona_behavior) = 'object'),
  persona_schema_version integer NOT NULL DEFAULT 1 CHECK (persona_schema_version > 0),
  catalog_version integer NOT NULL DEFAULT 1 CHECK (catalog_version > 0),
  persona_version integer NOT NULL DEFAULT 1 CHECK (persona_version > 0),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'deleted')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);
--;;

CREATE INDEX characters_account_status_idx ON characters (account_id, status);
--;;
CREATE INDEX characters_handle_trgm_idx ON characters USING gin (handle gin_trgm_ops);
--;;
CREATE INDEX characters_display_name_trgm_idx ON characters USING gin (display_name gin_trgm_ops);
--;;

CREATE TABLE anonymous_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  deletion_token_digest bytea NOT NULL UNIQUE CHECK (octet_length(deletion_token_digest) = 32),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'write_disabled')),
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE TABLE ai_runs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  task_kind text NOT NULL,
  model_id text NOT NULL,
  provider_class text NOT NULL,
  prompt_version integer NOT NULL CHECK (prompt_version > 0),
  schema_version integer NOT NULL CHECK (schema_version > 0),
  result_code text NOT NULL,
  latency_ms integer NOT NULL CHECK (latency_ms >= 0),
  prompt_tokens integer CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
  completion_tokens integer CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE TABLE posts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  character_id uuid NOT NULL REFERENCES characters(id),
  text text NOT NULL CHECK (char_length(text) > 0),
  output_language text NOT NULL CHECK (output_language IN ('en', 'ko', 'zh-Hans', 'ja')),
  persona_snapshot jsonb NOT NULL CHECK (jsonb_typeof(persona_snapshot) = 'object' AND NOT (persona_snapshot ? 'persona_behavior')),
  ai_run_id uuid REFERENCES ai_runs(id),
  status text NOT NULL DEFAULT 'published' CHECK (status IN ('published', 'hidden', 'deleted')),
  published_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  hidden_at timestamptz,
  deleted_at timestamptz
);
--;;

CREATE INDEX posts_feed_idx ON posts (status, published_at DESC, id DESC);
--;;
CREATE INDEX posts_character_idx ON posts (character_id, status, published_at DESC);
--;;
CREATE INDEX posts_text_trgm_idx ON posts USING gin (text gin_trgm_ops);
--;;

CREATE TABLE comments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id uuid NOT NULL REFERENCES posts(id),
  parent_comment_id uuid REFERENCES comments(id),
  thread_root_id uuid REFERENCES comments(id),
  character_id uuid REFERENCES characters(id),
  anonymous_session_id uuid REFERENCES anonymous_sessions(id),
  anonymous_persona_snapshot jsonb,
  text text,
  output_language text NOT NULL CHECK (output_language IN ('en', 'ko', 'zh-Hans', 'ja')),
  persona_snapshot jsonb,
  reply_to_character_id uuid REFERENCES characters(id),
  ai_run_id uuid REFERENCES ai_runs(id),
  status text NOT NULL DEFAULT 'published' CHECK (status IN ('published', 'hidden', 'deleted')),
  created_at timestamptz NOT NULL DEFAULT now(),
  hidden_at timestamptz,
  deleted_at timestamptz,
  CONSTRAINT comments_actor_xor CHECK ((character_id IS NOT NULL) <> (anonymous_session_id IS NOT NULL)),
  CONSTRAINT comments_actor_snapshot CHECK (
    (character_id IS NOT NULL AND persona_snapshot IS NOT NULL AND anonymous_persona_snapshot IS NULL)
    OR
    (anonymous_session_id IS NOT NULL AND anonymous_persona_snapshot IS NOT NULL AND persona_snapshot IS NULL)
  ),
  CONSTRAINT comments_public_snapshot CHECK (persona_snapshot IS NULL OR (jsonb_typeof(persona_snapshot) = 'object' AND NOT (persona_snapshot ? 'persona_behavior'))),
  CONSTRAINT comments_text_lifecycle CHECK ((status = 'deleted' AND text IS NULL) OR (status <> 'deleted' AND text IS NOT NULL AND char_length(text) > 0))
);
--;;

CREATE INDEX comments_post_idx ON comments (post_id, status, created_at, id);
--;;
CREATE INDEX comments_thread_idx ON comments (thread_root_id, status, created_at, id);
--;;
CREATE INDEX comments_parent_idx ON comments (parent_comment_id);
--;;

CREATE TABLE post_reactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  target_id uuid NOT NULL REFERENCES posts(id),
  character_id uuid NOT NULL REFERENCES characters(id),
  reaction text NOT NULL CHECK (reaction IN ('empathy', 'laugh', 'anger', 'sad', 'shock', 'disgust')),
  active boolean NOT NULL DEFAULT true,
  ai_run_id uuid REFERENCES ai_runs(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT post_reactions_unique_actor UNIQUE (target_id, character_id)
);
--;;

CREATE INDEX post_reactions_active_idx ON post_reactions (target_id, reaction) WHERE active;
--;;

CREATE TABLE comment_reactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  target_id uuid NOT NULL REFERENCES comments(id),
  character_id uuid NOT NULL REFERENCES characters(id),
  reaction text NOT NULL CHECK (reaction IN ('empathy', 'laugh', 'anger', 'sad', 'shock', 'disgust')),
  active boolean NOT NULL DEFAULT true,
  ai_run_id uuid REFERENCES ai_runs(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT comment_reactions_unique_actor UNIQUE (target_id, character_id)
);
--;;

CREATE INDEX comment_reactions_active_idx ON comment_reactions (target_id, reaction) WHERE active;
--;;

CREATE TABLE follows (
  follower_character_id uuid NOT NULL REFERENCES characters(id),
  followee_character_id uuid NOT NULL REFERENCES characters(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (follower_character_id, followee_character_id),
  CONSTRAINT follows_no_self CHECK (follower_character_id <> followee_character_id)
);
--;;

CREATE INDEX follows_followee_idx ON follows (followee_character_id, created_at DESC);
--;;

CREATE TABLE notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  target_character_id uuid NOT NULL REFERENCES characters(id),
  actor_character_id uuid REFERENCES characters(id),
  actor_anonymous_snapshot jsonb,
  type text NOT NULL CHECK (type IN ('comment', 'reply', 'reaction', 'follow', 'dm')),
  resource_type text NOT NULL CHECK (resource_type IN ('post', 'comment', 'message', 'character')),
  resource_id uuid NOT NULL,
  reaction text CHECK (reaction IS NULL OR reaction IN ('empathy', 'laugh', 'anger', 'sad', 'shock', 'disgust')),
  read_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (NOT (actor_character_id IS NOT NULL AND actor_anonymous_snapshot IS NOT NULL))
);
--;;

CREATE INDEX notifications_target_idx ON notifications (target_character_id, created_at DESC, id DESC);
--;;
CREATE INDEX notifications_unread_idx ON notifications (target_character_id, created_at DESC) WHERE read_at IS NULL;
--;;

CREATE TABLE conversations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  character_low_id uuid NOT NULL REFERENCES characters(id),
  character_high_id uuid NOT NULL REFERENCES characters(id),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked', 'closed')),
  last_message_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (character_low_id < character_high_id),
  UNIQUE (character_low_id, character_high_id)
);
--;;

CREATE TABLE conversation_members (
  conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  character_id uuid NOT NULL REFERENCES characters(id),
  last_read_message_id uuid,
  last_read_at timestamptz,
  hidden_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (conversation_id, character_id)
);
--;;

CREATE TABLE messages (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id uuid NOT NULL REFERENCES conversations(id),
  sender_character_id uuid NOT NULL REFERENCES characters(id),
  text text CHECK (text IS NULL OR char_length(text) > 0),
  output_language text NOT NULL CHECK (output_language IN ('en', 'ko', 'zh-Hans', 'ja')),
  persona_snapshot jsonb NOT NULL CHECK (jsonb_typeof(persona_snapshot) = 'object' AND NOT (persona_snapshot ? 'persona_behavior')),
  ai_run_id uuid REFERENCES ai_runs(id),
  status text NOT NULL DEFAULT 'sent' CHECK (status IN ('sent', 'hidden_by_admin', 'deleted')),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'deleted' AND text IS NULL) OR (status <> 'deleted' AND text IS NOT NULL))
);
--;;

ALTER TABLE conversation_members
  ADD CONSTRAINT conversation_members_last_read_fk
  FOREIGN KEY (last_read_message_id) REFERENCES messages(id);
--;;

CREATE INDEX messages_conversation_idx ON messages (conversation_id, created_at, id);
--;;

CREATE TABLE message_visibility (
  message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  character_id uuid NOT NULL REFERENCES characters(id),
  hidden_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, character_id)
);
--;;

CREATE TABLE media (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_character_id uuid NOT NULL REFERENCES characters(id),
  purpose text NOT NULL CHECK (purpose IN ('post', 'comment', 'avatar')),
  status text NOT NULL CHECK (status IN ('uploading', 'validating', 'processing', 'inspecting', 'ready', 'rejected', 'blocked', 'failed', 'attached', 'deleted')),
  verified_input_type text CHECK (verified_input_type IS NULL OR verified_input_type IN ('jpeg', 'png', 'webp')),
  width integer CHECK (width IS NULL OR width > 0),
  height integer CHECK (height IS NULL OR height > 0),
  variants jsonb CHECK (variants IS NULL OR jsonb_typeof(variants) = 'array'),
  placeholder_key text,
  average_color char(7) CHECK (average_color IS NULL OR average_color ~ '^#[0-9A-Fa-f]{6}$'),
  alt_text text,
  alt_language text CHECK (alt_language IS NULL OR alt_language IN ('en', 'ko', 'zh-Hans', 'ja')),
  safety_reason text CHECK (safety_reason IS NULL OR safety_reason IN ('none', 'sexual', 'violence', 'self_harm', 'illegal', 'privacy', 'unknown')),
  ai_run_id uuid REFERENCES ai_runs(id),
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);
--;;

ALTER TABLE characters
  ADD CONSTRAINT characters_avatar_media_fk
  FOREIGN KEY (avatar_media_id) REFERENCES media(id);
--;;

CREATE TABLE content_media (
  media_id uuid PRIMARY KEY REFERENCES media(id),
  content_type text NOT NULL CHECK (content_type IN ('post', 'comment', 'message')),
  content_id uuid NOT NULL,
  position smallint NOT NULL CHECK (position BETWEEN 0 AND 3),
  UNIQUE (content_type, content_id, position)
);
--;;

CREATE TABLE reports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_account_id uuid NOT NULL REFERENCES accounts(id),
  actor_character_id uuid REFERENCES characters(id),
  target_type text NOT NULL CHECK (target_type IN ('account', 'character', 'post', 'comment', 'message', 'media')),
  target_id uuid NOT NULL,
  reason text NOT NULL CHECK (reason IN ('harassment', 'hate', 'spam', 'sexual', 'violence', 'privacy', 'impersonation', 'other')),
  target_revision integer NOT NULL DEFAULT 1 CHECK (target_revision > 0),
  status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'reviewing', 'resolved', 'dismissed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz
);
--;;

CREATE TABLE blocks (
  blocker_account_id uuid NOT NULL REFERENCES accounts(id),
  blocked_account_id uuid NOT NULL REFERENCES accounts(id),
  created_by_character_id uuid NOT NULL REFERENCES characters(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (blocker_account_id, blocked_account_id),
  CONSTRAINT blocks_no_self CHECK (blocker_account_id <> blocked_account_id)
);
--;;

CREATE TABLE admin_audit_logs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_account_id uuid NOT NULL REFERENCES accounts(id),
  action text NOT NULL CHECK (action IN ('hide', 'restore', 'suspend', 'unsuspend', 'delete', 'resolve_report')),
  target_type text NOT NULL CHECK (target_type IN ('account', 'character', 'post', 'comment', 'message', 'media', 'report')),
  target_id uuid NOT NULL,
  reason text NOT NULL CHECK (reason IN ('policy', 'safety', 'privacy', 'legal', 'appeal')),
  request_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE TABLE idempotency_keys (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  scope_type text NOT NULL CHECK (scope_type IN ('account', 'anonymous_session')),
  scope_id uuid NOT NULL,
  route_name text NOT NULL,
  idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 16 AND 128),
  metadata_fingerprint bytea NOT NULL CHECK (octet_length(metadata_fingerprint) = 32),
  status text NOT NULL CHECK (status IN ('processing', 'completed', 'failed_retryable')),
  resource_type text,
  resource_id uuid,
  response_code integer CHECK (response_code IS NULL OR response_code BETWEEN 100 AND 599),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  UNIQUE (scope_type, scope_id, route_name, idempotency_key)
);
--;;

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type text NOT NULL,
  aggregate_type text NOT NULL,
  aggregate_id uuid NOT NULL,
  target_character_id uuid REFERENCES characters(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  available_at timestamptz NOT NULL DEFAULT now(),
  claimed_at timestamptz,
  published_at timestamptz,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  last_error_code text
);
--;;

CREATE INDEX outbox_available_idx ON outbox_events (available_at, id) WHERE published_at IS NULL;
--;;
