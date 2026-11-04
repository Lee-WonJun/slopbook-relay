ALTER TABLE posts ALTER COLUMN text DROP NOT NULL;
--;;
UPDATE posts SET text = NULL WHERE status = 'deleted';
--;;
ALTER TABLE posts
  ADD CONSTRAINT posts_text_lifecycle
  CHECK ((status = 'deleted' AND text IS NULL)
         OR (status <> 'deleted' AND text IS NOT NULL AND char_length(text) > 0));
