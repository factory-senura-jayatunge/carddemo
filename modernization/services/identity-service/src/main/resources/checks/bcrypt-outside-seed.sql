-- Active users whose password is still a {bcrypt} hash, outside the named demo seed users
-- (UserSeeder.DEMO_USERS, public README credentials) and outside the users who must change their
-- password at the next sign-on (their change writes Argon2id). Sign-on replaces a {bcrypt} hash
-- after its first successful check, so a row listed here has not signed on since Argon2id became
-- the hash: a migrated or imported user to follow up. Lists user IDs only, never hashes.
SET NOCOUNT ON;
SELECT RTRIM(user_id) AS user_id
FROM iam.app_user
WHERE password_hash LIKE '{bcrypt}%'
  AND deleted_at IS NULL
  AND must_change_password = 0
  AND user_id NOT IN (N'ADMIN001', N'ADMIN002', N'ADMIN003', N'ADMIN004', N'ADMIN005',
                      N'USER0001', N'USER0002', N'USER0003', N'USER0004', N'USER0005')
ORDER BY user_id;
