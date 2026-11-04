ALTER TABLE posts DROP CONSTRAINT posts_text_lifecycle;
--;;
UPDATE posts SET text = '[deleted]' WHERE status = 'deleted' AND text IS NULL;
--;;
ALTER TABLE posts ALTER COLUMN text SET NOT NULL;
