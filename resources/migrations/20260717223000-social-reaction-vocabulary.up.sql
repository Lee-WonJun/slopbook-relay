ALTER TABLE post_reactions DROP CONSTRAINT post_reactions_reaction_check;
--;;
ALTER TABLE comment_reactions DROP CONSTRAINT comment_reactions_reaction_check;
--;;
ALTER TABLE notifications DROP CONSTRAINT notifications_reaction_check;
--;;

UPDATE post_reactions
   SET reaction = CASE reaction
     WHEN 'empathy' THEN 'support'
     WHEN 'laugh' THEN 'funny'
     WHEN 'anger' THEN 'like'
     WHEN 'sad' THEN 'support'
     WHEN 'shock' THEN 'insightful'
     WHEN 'disgust' THEN 'like'
     ELSE reaction
   END;
--;;

UPDATE comment_reactions
   SET reaction = CASE reaction
     WHEN 'empathy' THEN 'support'
     WHEN 'laugh' THEN 'funny'
     WHEN 'anger' THEN 'like'
     WHEN 'sad' THEN 'support'
     WHEN 'shock' THEN 'insightful'
     WHEN 'disgust' THEN 'like'
     ELSE reaction
   END;
--;;

UPDATE notifications
   SET reaction = CASE reaction
     WHEN 'empathy' THEN 'support'
     WHEN 'laugh' THEN 'funny'
     WHEN 'anger' THEN 'like'
     WHEN 'sad' THEN 'support'
     WHEN 'shock' THEN 'insightful'
     WHEN 'disgust' THEN 'like'
     ELSE reaction
   END
 WHERE reaction IS NOT NULL;
--;;

ALTER TABLE post_reactions
  ADD CONSTRAINT post_reactions_reaction_check
  CHECK (reaction IN ('like', 'love', 'insightful', 'celebrate', 'support', 'funny'));
--;;
ALTER TABLE comment_reactions
  ADD CONSTRAINT comment_reactions_reaction_check
  CHECK (reaction IN ('like', 'love', 'insightful', 'celebrate', 'support', 'funny'));
--;;
ALTER TABLE notifications
  ADD CONSTRAINT notifications_reaction_check
  CHECK (reaction IS NULL OR reaction IN ('like', 'love', 'insightful', 'celebrate', 'support', 'funny'));
