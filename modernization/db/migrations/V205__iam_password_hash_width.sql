-- Password hash width (identity-service.md, section 6).
--
-- New hashes are Argon2id in Spring Security's {id} format:
-- {argon2}$argon2id$v=19$m=19456,t=2,p=1$<22-char salt>$<43-char hash> is 105
-- characters, more than VARCHAR(100). The value is ASCII, so the column stays
-- VARCHAR. No index, constraint or view uses the column, and existing {bcrypt}
-- hashes are kept as they are.
ALTER TABLE iam.app_user ALTER COLUMN password_hash VARCHAR(255) NOT NULL;
GO
