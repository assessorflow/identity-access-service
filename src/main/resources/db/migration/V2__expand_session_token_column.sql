-- RS256 JWTs are longer than 512 chars; expand token column
-- Drop unique index first since TEXT columns can't have one
ALTER TABLE sessions DROP INDEX token;
ALTER TABLE sessions MODIFY COLUMN token TEXT NOT NULL;
