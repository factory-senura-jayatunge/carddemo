# identity-service (draft)

Status: draft written before `docs/modernization/architecture/billpay-slice.md`. Where the contract differs, the contract wins and this file is updated.

Replaces COSGN00C (sign-on) now; COUSR00C-03C (user administration) later. Port 28081, heap 256 MiB.

## Endpoints (Bill Pay slice)

| Method and path | Result |
| :-- | :-- |
| `POST /api/auth/sign-on` `{userId, password}` | `200 {userId, userType, roles, home}` and the cookies below; errors are problem details with `code`, `message`, `field` |
| `POST /api/auth/refresh` | rotates the refresh token, issues a new access token |
| `POST /api/auth/sign-off` | revokes the refresh-token family, clears the cookies; `{message: "Thank you for using CardDemo application..."}` |
| `GET /api/auth/me` | `{userId, userType, roles, features}` from the access token |
| `GET /api/auth/.well-known/jwks.json` | public RSA keys (current and previous) |

## Tokens and cookies

- Access token: JWT RS256, 15 minutes. Claims `iss`, `aud`, `sub` (user ID), `roles` (`ADMIN` or `USER`), `utype` (legacy type), `iat`, `exp`, `jti`, `sid` (refresh-token family). `kid` is the RFC 7638 thumbprint.
- Refresh token: 256-bit random opaque value; only its SHA-256 is stored (`iam.refresh_token`). Each use rotates it; presenting an already rotated token revokes the whole family (reuse detection). Idle limit 15 minutes, absolute limit 8 hours (D-05 example values).
- Cookies: access token on `Path=/api`, refresh token on `Path=/api/auth`, both `HttpOnly; SameSite=Strict; Secure`. CSRF is a double-submit cookie (`XSRF-TOKEN`, readable by the web app) checked against the `X-XSRF-TOKEN` header on unsafe methods; it needs no server state, so every service can check it.
- Signing key: an RSA 2048 PKCS#8 PEM outside git, path from configuration. The JWKS also lists the previous public key during a rotation.

## Fidelity switches (sign-on)

| Item | Legacy | Fixed |
| :-- | :-- | :-- |
| BR-SGN-003, DEF-SGN-011, Q-SGN-002 (D-04) | password upper-cased before hashing and checking | case-sensitive password; the user ID is still upper-cased (BR-SGN-001) |
| Q-SGN-001 (D-04) | no reset | migrated users must set a new password at their first sign-on |
| DEF-SGN-002, Q-SGN-003 (D-05) | no attempt limit | 5 failures lock the user for 15 minutes; per-client and per-user-ID rate limit |
| DEF-SGN-003, Q-SGN-004 (D-05) | `User not found. Try again ...` / `Wrong Password. Try again ...` | one neutral message for unknown user, wrong password and locked user |
| DEF-SGN-005, Q-SGN-005 (D-02) | any type other than `A` signs on as `USER` | types other than `A` and `U` are refused |

Not switched: plain-text storage (DEF-SGN-001) is never kept; every password is a salted bcrypt hash. Each hash records how its input was prepared (`password_case`), so a hash stays verifiable when the case switch changes. Sign-on audit is always on, because it has no visible effect.

## Schema

`modernization/db/migrations/V200__iam_schema.sql`: `iam.app_user`, `iam.refresh_token`, `iam.audit_event`. The user table already holds what user administration needs (names, type, `ebcdic_sort_key` for the legacy list order, audit columns, logical delete, `row_version`).
