ALTER TABLE post_reactions DROP CONSTRAINT post_reactions_reaction_check;
--;;
ALTER TABLE comment_reactions DROP CONSTRAINT comment_reactions_reaction_check;
--;;
ALTER TABLE notifications DROP CONSTRAINT notifications_reaction_check;
--;;

UPDATE post_reactions
   SET reaction = CASE reaction
     WHEN 'like' THEN 'empathy'
     WHEN 'love' THEN 'empathy'
     WHEN 'insightful' THEN 'shock'
     WHEN 'celebrate' THEN 'laugh'
     WHEN 'support' THEN 'empathy'
     WHEN 'funny' THEN 'laugh'
     ELSE reaction
   END;
--;;

UPDATE comment_reactions
   SET reaction = CASE reaction
     WHEN 'like' THEN 'empathy'
     WHEN 'love' THEN 'empathy'
     WHEN 'insightful' THEN 'shock'
     WHEN 'celebrate' THEN 'laugh'
     WHEN 'support' THEN 'empathy'
     WHEN 'funny' THEN 'laugh'
     ELSE reaction
   END;
--;;

UPDATE notifications
   SET reaction = CASE reaction
     WHEN 'like' THEN 'empathy'
     WHEN 'love' THEN 'empathy'
     WHEN 'insightful' THEN 'shock'
     WHEN 'celebrate' THEN 'laugh'
     WHEN 'support' THEN 'empathy'
     WHEN 'funny' THEN 'laugh'
     ELSE reaction
   END
 WHERE reaction IS NOT NULL;
--;;

ALTER TABLE post_reactions
  ADD CONSTRAINT post_reactions_reaction_check
  CHECK (reaction IN ('empathy', 'laugh', 'anger', 'sad', 'shock', 'disgust'));
--;;
ALTER TABLE comment_reactions
  ADD CONSTRAINT comment_reactions_reaction_check
  CHECK (reaction IN ('empathy', 'laugh', 'anger', 'sad', 'shock', 'disgust'));
--;;
ALTER TABLE notifications
  ADD CONSTRAINT notifications_reaction_check
  CHECK (reaction IS NULL OR reaction IN ('empathy', 'laugh', 'anger', 'sad', 'shock', 'disgust'));
