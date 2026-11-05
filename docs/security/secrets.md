# Secrets and credentials

CardDemo is a sample application. It ships with demo sign-on credentials and some
site-specific connection details in sample JCL. None of them are real secrets, but a real
deployment must replace them. This page lists where credentials appear in the repository,
what to do about each one, and the rules for keeping new secrets out of git.

To report a security vulnerability, use the
[AWS vulnerability reporting page](https://aws.amazon.com/security/vulnerability-reporting/).
Do not open a public issue.

## Demo sign-on credentials

The README documents two sign-ons for the CC00 transaction:

| User ID  | Type  | Password  |
| :------- | :---- | :-------- |
| ADMIN001 | Admin | PASSWORD  |
| USER0001 | User  | PASSWORD  |

These are **demo-only seed data**. They are records in the user security file
(`AWS.M2.CARDDEMO.USRSEC.PS`, loaded into the `USRSEC` VSAM file), whose layout is
`SEC-USER-DATA` in `app/cpy/CSUSR01Y.cpy`. The same seed records, ten users in total
(ADMIN001-ADMIN005 and USER0001-USER0005), all with the password `PASSWORD`, appear in:

- `app/jcl/DUSRSECJ.jcl` (in-stream data loaded by IEBGENER, then REPRO into the KSDS)
- `app/jcl/ESDSRRDS.jcl` (in-stream data for the ESDS and RRDS examples)
- `app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`
- the user security data inside `samples/m2/mf/CardDemo_runtime.zip` and
  `samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip`

Nothing outside a demo region trusts these credentials. Treat them as public.

### How the application handles passwords

Know these limits before you put CardDemo anywhere other than a private test region:

- `SEC-USR-PWD` is `PIC X(08)` and is stored **in clear text** in the USRSEC file.
- `COSGN00C` upper-cases the entered password and compares it byte for byte with
  `SEC-USR-PWD`. There is no hashing, lockout or expiry.
- `COUSR02C` (Update User, CU02) moves the stored password into the `PASSWD` field of the
  COUSR02 map. The field is defined `DRK` (non-display), so it is hidden on the terminal,
  but the value still travels in the 3270 data stream.
- `COUSR01C` (Add User, CU01) writes the typed password to the file unchanged.

### What a real deployment must do

1. Reset every seeded user before the region is reachable by anyone else. Either delete
   the ten seed records after loading, or replace the in-stream data in `DUSRSECJ` with
   your own users before you run it.
2. Prefer your external security manager. CardDemo's README lists RACF as a core technology,
   and `samples/jcl/RACFCMDS.jcl` shows sample RACF commands that protect CICS transactions
   (`RALT GCICSTRN ... ADDMEM(CT02)`) and connect users to groups. Authenticate users there
   instead of relying on the USRSEC file.
3. Keep real credentials in a secrets manager, never in source, JCL or data files. For
   AWS Mainframe Modernization deployments, use AWS Secrets Manager. The bundled
   Rocket (Micro Focus) runtime sample already does this: `app_config.json` inside
   `samples/m2/mf/CardDemo_runtime.zip` refers to its database credentials through
   `secret-manager-arn` entries instead of storing user names and passwords.
4. If you modernize the sign-on, store salted password hashes, not clear text, and stop
   sending stored passwords to the screen.

## Site-specific values in sample JCL and scripts

| Location | What it contains | Action |
| :------- | :--------------- | :----- |
| `app/jcl/FTPJCL.JCL` | The placeholder FTP host `MY.FTP.SERVER.COM` on the first `SYSIN` line and a `NETRC` DD (`&SYSUID..NETRC`). No user ID or password. | Replace the host with your server. Keep the login in your `NETRC` data set; see [FTP credentials for FTPJCL](#ftp-credentials-for-ftpjcl). Never commit your own host or login. |
| `scripts/run_full_batch.sh`, `scripts/run_posting.sh`, `scripts/run_interest_calc.sh`, `scripts/remote_compile.sh`, `scripts/remote_refresh.sh`, `scripts/remote_submit.sh`, `scripts/upld_module.sh` | `tnftp localhost 2121` through an SSH tunnel to the mainframe. No credentials in the scripts. | `tnftp` reads the login from the operator's `~/.netrc`. Keep that file outside the repository with mode `0600`. `.netrc` is listed in `.gitignore`. |
| `samples/m2/mf/CardDemo_runtime.zip` (`app_config.json`) | Two `secret-manager-arn` references that include an AWS account ID | Not secrets, but they identify the original account. Point them at secrets in your own account when you deploy the sample. |

A scan of the tracked sources, JCL, scripts and both runtime archives found no access keys,
tokens or private keys. The only credential values in the current tree are the demo
sign-on passwords. Earlier revisions of `FTPJCL.JCL` in git history carried an in-stream
server address, FTP user ID and password for the original demo site. If those values were
ever valid for a server you run, rotate them.

## FTP credentials for FTPJCL

`FTPJCL` runs the z/OS FTP client in batch (`PGM=FTP`). The client takes the host from the
first `SYSIN` line and the user ID and password from the data set on the `NETRC` DD, so
the JCL holds no login. Set this up once for each user ID that submits the job (your TSO
user ID or the scheduler's ID):

1. Allocate `userid.NETRC` as a sequential data set, `RECFM=FB`, `LRECL=80`, one track.
   A PDS member also works if you change the `NETRC` DD to name it.
2. Add one line for the server. The keywords must be lower case, and the data set must
   not contain comments:

   ```text
   machine MY.FTP.SERVER.COM login userid password pw
   ```

   Use your real host, user ID and password. The `machine` value must match the host on
   the first `SYSIN` line of the job exactly.
3. Protect the data set so only its owner can read it, for example with
   `ADDSD 'userid.NETRC' UACC(NONE)`.
4. Change `MY.FTP.SERVER.COM` in `FTPJCL.JCL` to the same host, submit the job, and check
   that the job log shows a successful login before the `PUT`.

To pass the host as a parameter instead, uncomment the `PARM` line in the job, add a comma
after `REGION=2048K` on the `EXEC` statement and delete the host line from `SYSIN`.

## Local environment files

- `.env` and `.env.*` are ignored by git (see `.gitignore`). Only `.env.example` is
  committed, and it holds variable names and harmless defaults, never real values.
- Copy `.env.example` to `.env` and fill in values locally.
- Do not paste real hosts, user IDs or passwords into issues, pull requests or job output
  you attach to them.

## CI

- Workflows read credentials only from GitHub encrypted secrets through `secrets.*`
  expressions, for example `${{ secrets.GITHUB_TOKEN }}` in
  `.github/workflows/label-sync.yml`.
- Do not echo secrets, write them to files that are uploaded as artifacts, or pass them on a
  command line that is logged.
- Grant each job the narrowest `permissions:` block it needs.

## If a secret is committed

1. Revoke or rotate it first. Removing it from git does not make it safe again.
2. Remove it from the working tree and add the file pattern to `.gitignore` if needed.
3. Ask a maintainer (see `.github/CODEOWNERS`) whether history must be rewritten.
