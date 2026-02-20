ALTER TABLE app_user ADD COLUMN IF NOT EXISTS grade SMALLINT NOT NULL DEFAULT 1;

UPDATE app_user
SET grade = CASE
    WHEN upper(role) = 'ADMIN' AND grade < 6 THEN 6
    ELSE grade
END;
