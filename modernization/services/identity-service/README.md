# identity-service

Sign-on (COSGN00C), password change, sessions and the access tokens every CardDemo service
accepts. It owns the `iam` schema: migrations `V200` to `V299` and `R__grants_iam.sql` in
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

Not switched: passwords are never stored in plain text (DEF-SGN-001); every one is a salted
bcrypt hash of the prepared value (trailing spaces trimmed, upper-cased in legacy mode).
Sign-on outcomes are always audited to `iam.audit_event`, without the password.

## Tests

```sh
CARDDEMO_TEST_MSSQL_DB=carddemo_test_identity scripts/with_env.py ./mvnw -pl services/identity-service verify
```

The tests run against their own freshly migrated database on the shared SQL Server, connected as
the service login, so they run under the real grants. `OpenApiExportTest` writes
`target/openapi/identity-service.json` and fails when it differs from
`modernization/web/openapi/identity-service.json`; it is skipped while that snapshot is not
committed.
