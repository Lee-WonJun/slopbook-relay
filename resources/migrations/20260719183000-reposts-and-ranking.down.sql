DELETE FROM notifications WHERE type = 'repost';
--;;

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
--;;

ALTER TABLE notifications
  ADD CONSTRAINT notifications_type_check
  CHECK (type IN ('comment', 'reply', 'reaction', 'follow', 'dm'));
--;;

DROP TABLE IF EXISTS post_reposts;
