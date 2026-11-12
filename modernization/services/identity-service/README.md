# identity-service

Sign-on (COSGN00C), password change, sessions, the access tokens every CardDemo service
accepts, and user administration (COUSR00C to COUSR03C). It owns the `iam` schema: migrations `V200` to `V299` and `R__grants_iam.sql` in
`modernization/db/migrations`. Port 28081.

## Run

The database must be migrated first (`make -C modernization db-reset`, which runs db-migrate).
From `modernization/`:

```sh
./mvnw -pl services/identity-service -am -DskipTests package
scripts/with_env.py java -Xmx256m -jar services/identity-service/target/identity-service-0.1.0-SNAPSHOT-exec.jar \
  --spring.profiles.active=demo
```

| Setting | Source | Purpose |
| :-- | :-- | :-- |
| `CARDDEMO_JWT_KEY_FILE` | the Makefile; `var/secrets/identity-jwt.pem` | PKCS#8 PEM of the RS256 signing key. The service does not start without it. |
| `CARDDEMO_DB_NAME`, `MSSQL_HOST_PORT` | `.env` | Database and port (defaults `carddemo`, 21433). |
| `CARDDEMO_DB_IDENTITY_PASSWORD` | `.env` | Password of login `<CARDDEMO_DB_NAME>_identity` (override the login with `CARDDEMO_DB_IDENTITY_USER`). |
| `CARDDEMO_SEED_USER_PASSWORD` | `.env` | Profile `demo`: the password of the ten DUSRSECJ users seeded into an empty `iam.app_user`. |
| `CARDDEMO_BOOTSTRAP_ADMIN_PASSWORD` | environment | Without profile `demo`: the one `ADMIN001` seeded into an empty table; it must change its password at first sign-on. |
| `CARDDEMO_LEDGER_DIR` | environment | Directory of `fidelity.toml` (`modernization/ledger`). Without it every switch is legacy and a warning is logged. |

Profile `demo` also sets `carddemo.security.cookie-secure=false`, for plain http on 127.0.0.1.
A single switch can be forced with `--carddemo.fidelity.DEF-SGN-011=legacy`.

## API

| Method and path | Purpose |
| :-- | :-- |
| `POST /api/auth/sign-on` | `{userId, password}`; sets `cd_at`, `cd_rt` and `XSRF-TOKEN`. |
| `POST /api/auth/password` | `{userId?, currentPassword, newPassword}`; changes the password and signs on. |
| `POST /api/auth/refresh` | Rotates the refresh token; needs `X-XSRF-TOKEN`. |
| `POST /api/auth/sign-off` | Ends the session; needs `X-XSRF-TOKEN`. |
| `GET /api/auth/me` | The session's user. |
| `GET /api/auth/.well-known/jwks.json` | The public signing key for the other services. |
| `GET /api/features` | Installed modules, converted units and the UI switch `DEF-BILL-014`. |
| `GET /api/users?start=&startKey=&after=&before=&size=` | Role `ADMIN`. Active users in user ID order, 10 a page; `start` is a case-insensitive prefix filter (`startKey` the same in key form) and the page tokens keep it. |
| `POST /api/users` | Role `ADMIN`. `{firstName, lastName, userId, password, userType}`; `201` with the user, `Location` (key form) and `ETag`. |
| `GET /api/users/{userId}` | Role `ADMIN`. The user with its `version` and `ETag`; never a password. |
| `PUT /api/users/{userId}` | Role `ADMIN`, `If-Match`. `{firstName, lastName, userType, password?}`; a blank password keeps the current one. |
| `DELETE /api/users/{userId}` | Role `ADMIN`, `If-Match`. Logical delete: the row stays and a later add of the same ID reuses it; not one's own user, not the last active administrator. |
| `GET`, `PUT`, `DELETE /api/users/key/{userKey}` | The same three calls for any user ID: `userKey` is base64url (no padding) of the ID's UTF-8 bytes. Tomcat and the Spring Security firewall refuse an encoded `/`, `\`, `%`, `;` or NUL and the segments `.` and `..` in a path, which user IDs may hold; the page always uses this form. A bad key is `400 INVALID_REQUEST`. |

Errors are `application/problem+json` with `code`, `message` (the screen text), `field` and
`severity`. The OpenAPI document is at `/v3/api-docs`.

Access tokens live 300 seconds and are checked with no clock skew; a session ends after
15 idle minutes or 8 hours.

## Fidelity switches

| Item | Legacy | Fixed |
| :-- | :-- | :-- |
| `DEF-SGN-011` | Password upper-cased before the check | Case-sensitive; `must_change_password` forces a change |
| `DEF-SGN-002` | No attempt counting | 5 failures lock the user for 15 minutes; a client address with 30 failed attempts in a minute gets `429 TOO_MANY_ATTEMPTS` on every sign-on and password change until the window passes (`carddemo.identity.rate-limit.failures` and `.window`) |
| `DEF-SGN-003` | `User not found` / `Wrong Password` | One neutral message |
| `DEF-SGN-005` | Any type other than `A` signs on as `USER` | Types other than `A` and `U` refused |
| `DEF-BILL-014` | Reported by `/api/features` for the web app | |

Not switched: passwords are never stored in plain text (DEF-SGN-001). The prepared value
(trailing spaces trimmed, `a` to `z` upper-cased in legacy mode) is hashed with Argon2id
(`Argon2PasswordEncoder(16, 32, 1, 19456, 2)`, OWASP's minimum); `{bcrypt}` hashes stored before
still verify, and the first successful sign-on replaces each one with an Argon2id hash of the same
prepared value, in the session's transaction and guarded on the old hash (only `password_hash`
changes, which moves `row_version` once). That transaction reads the user and its sign-on state
again under an update lock: a delete, password change, lock, user type change or forced change
committed after the check refuses the sign-on (and the password change) for that reason, with no
session and no failure counted. Without the upgrade a `{bcrypt}` user's
sign-on time would differ from an unknown user's, which costs one Argon2 check.
`scripts/identity_bcrypt_check.py` (through `scripts/with_env.py`) lists the active users outside
the demo seed, without a forced change, whose hash is still `{bcrypt}`
(`src/main/resources/checks/bcrypt-outside-seed.sql`), and fails when there is one; it prints user
IDs only. A password above 72 UTF-8 bytes never matches a bcrypt hash,
since bcrypt reads only 72; that check still runs one bcrypt check against a dummy hash, so its
time does not tell a bcrypt hash from an Argon2 one. At most two hash or check calls run at once, so a burst of sign-ons
stays within `-Xmx256m` (19 MiB each). `PasswordHashingBench` (test sources) prints their timings:

```sh
./mvnw -pl services/identity-service dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test
java -Xmx256m -cp services/identity-service/target/test-classes:services/identity-service/target/classes:$(cat /tmp/cp.txt) \
  org.carddemo.identity.user.PasswordHashingBench
```
Sign-on outcomes are always audited to `iam.audit_event`, without the password.

User administration follows the identity-service contract: the user ID is canonical (trailing
pad and leading spaces removed, at most 8 characters of U+0000 to U+00FF, no space inside, only
`a` to `z` upper-cased), passwords follow the new-password rule of `DEF-SGN-011`, and add,
save and delete answer `503 BATCH_WINDOW` while a batch window is open. Each change writes one
`ops.audit` row with the changed field names only, and a set password, a type change or a delete
ends the user's open sessions.

## Tests

```sh
CARDDEMO_TEST_MSSQL_DB=carddemo_test_identity scripts/with_env.py ./mvnw -pl services/identity-service verify
```

The tests run against their own freshly migrated database on the shared SQL Server, connected as
the service login, so they run under the real grants. `OpenApiExportTest` writes
`target/openapi/identity-service.json` and fails when it differs from
`modernization/web/openapi/identity-service.json`; it is skipped while that snapshot is not
committed. `UserKeysTest` and `UserIdPathTransportProbeTest` (real Tomcat and firewall, a stand-in
controller) need no database; `UserIdTransportPilotTest` runs the same user IDs through the real
service over HTTP.
