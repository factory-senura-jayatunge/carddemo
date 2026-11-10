# Contract: batch-service job execution API

Status: **approved 2026-11-10** by the architect. This is revision 3 of the build-data draft (`build-test/contracts/batch-execution.md`), with infra's review points and the architect's required changes folded in. Owner: build-data, which implements it in batch-service; infra implements `SpringBatchJobOperator` and the DAGs against it. Changes go through this document first. Amended 2026-11-10: D-33 matching steps and `expiryDays` on `postDailyTransactionsJob`. Where [batch.md](batch.md) or [api-security.md](api-security.md#batch-service-internal) differ, this document wins.

## Transport

- Base URL `http://127.0.0.1:<batch-service port>`. `/internal/*` listens on loopback only.
- Every request sends `Authorization: Bearer <CARDDEMO_BATCH_INTERNAL_TOKEN>`, compared in constant time and never logged. A missing or wrong token gets `401 UNAUTHENTICATED` (not retryable).
- Bodies are `application/json`. Errors are `application/problem+json` in the platform `ApiProblem` shape (`type`, `title`, `status`, `code`, `message`, `field`, `severity`), plus the members listed per code. `field` names the offending request member on a `400` and is null otherwise.
- No request carries a file path. No body carries record values or exception text. **No caller-supplied value (`runKey`, `windowRunKey`) is ever used in a file or folder name.** Output folders are named by IDs that batch-service generates (section 2.3).

## 1. Cycles: `POST /internal/cycles`

Request: `{"businessDate": "2022-07-06", "interest": "always" | "month_end" | "never"}`.

batch-service resolves the policy into a stored yes or no (`month_end` means `businessDate` is the last day of its month) and stores `batch.cycle.interest_parm` (`yyyyMMdd00` when due, NULL when not).

Cycle body (every success and every cycle `409`):

```json
{"cycleId": 7, "businessDate": "2022-07-06", "attempt": 1, "interest": true, "interestParm": "2022070600",
 "status": "OPEN", "openedAt": "2022-07-06T21:00:00.000Z", "closedAt": null}
```

- `interest` is the stored boolean; the DAG's `interest_due` branch reads it and nothing else.
- `attempt` is `batch.cycle.attempt`: 1, raised by each `cycle-rerun` (section 7).
- `status` is `OPEN` from registration until the DAG's completing window close, then `COMPLETED`.

| Response | When | Retry |
| :-- | :-- | :-- |
| `201` + cycle | New cycle, `OPEN`. | Safe to repeat. |
| `200` + cycle | The cycle exists, is `OPEN`, and the resolved `interest` is equal. The DAG resumes it: completed jobs answer `JOB_INSTANCE_COMPLETE`, failed ones restart. | Safe to repeat. |
| `409 CYCLE_COMPLETED` + cycle | The cycle is `COMPLETED`. The DAG fails here, before it opens the window, so a re-triggered run for a finished date never blocks online writes. | No. |
| `409 CYCLE_PARAMETERS_DIFFER` + cycle | The resolved `interest` differs from the stored one. | No. |
| `400 INVALID_REQUEST` | Missing or unparsable `businessDate`, unknown `interest`. | No. |

A V4xx migration narrows the `batch.cycle.status` check to `OPEN` and `COMPLETED`.

## 2. Launch: `POST /internal/jobs/{jobName}/executions`

Request: `{"params": {...}}`. batch-service builds the job parameters. Airflow never sends `run.id`, and no job uses an incrementer on this path.

### 2.1 Identity and parameters

| Kind | Jobs | Identifying parameters | Non-identifying parameters |
| :-- | :-- | :-- | :-- |
| cycle | `archiveMasterJob`, `ingestDailyFileJob`, `postDailyTransactionsJob`, `calculateInterestJob`, `statementJob`, `applyRetentionJob`, `purgePendingAuthorizationsJob` | `cycleId` (request), `businessDate` and `attempt` (from `batch.cycle`) | `interestParm` (from the cycle, `calculateInterestJob`); `expiryDays` and `commitInterval` (from configuration, the purge); `expiryDays` (the same setting, `postDailyTransactionsJob`, for D-33 matching). A request `businessDate`, if sent, must equal the cycle's. |
| report | `transactionReportJob` | `runId` (an existing `batch.report_run` row) | none: the job uses the row's stored parameters, not the request's |
| run-keyed | `categoryBalanceListingJob`, `dataLoadJob`, `disclosureGroupReloadJob`, `transactionTypeMaintenanceJob`, `exportMastersJob`, `importMigrationJob` | `runKey` = `"<dag_id>/<run_id>/<task_id>"` from the Airflow context, 1 to 250 characters | `businessDate` (required: the DAG's logical date or `conf` override; the timestamps the job writes take their date from it); `windowRunKey` (required for the jobs that need a window, section 6); `exportId` (required, `importMigrationJob`) |

- `make load` launches `dataLoadJob` directly with `runKey = "cli/<host>/<UTC timestamp to the microsecond>/<random UUID>"` and opens and closes its own window around the launch (section 6).
- Non-identifying parameters are fixed at the first launch of an instance and stored with it. A restart uses the stored values. A restart request with a different value gets `400 INVALID_JOB_PARAMETERS` with `field`.
- `categoryBalanceListingJob` is run-keyed: it is read-only and has no `batch.report_run` creator. If a screen ever triggers it, it gets a report-runs endpoint like the transaction report, through this document.

### 2.2 Answers

Every job-instance answer, `202` or `409`, has the same members:

```json
{"executionId": 123, "jobName": "postDailyTransactionsJob", "executionStatus": "STARTED",
 "exitStatus": "EXECUTING", "rejectCount": 0, "restart": false}
```

`executionStatus` is the Spring Batch `BatchStatus`. `exitStatus` is `COMPLETED`, `COMPLETED_WITH_REJECTS` or `FAILED` once terminal, otherwise `EXECUTING` or `UNKNOWN`. `rejectCount` is an integer. `restart` is on `202` only.

| Response | When | `executionId` |
| :-- | :-- | :-- |
| `202` | A first run, or a restart (`restart: true`) of an instance whose last execution is `FAILED` or `STOPPED` and whose job allows restart. This includes an execution that the launch has just recovered (section 4.4). | the new execution |
| `409 JOB_EXECUTION_RUNNING` | The last execution is `STARTING`, `STARTED` or `STOPPING`, and it is not recoverable (section 4.1). | the running execution |
| `409 JOB_INSTANCE_COMPLETE` | The last execution is `COMPLETED`. | the completed execution |
| `409 JOB_INSTANCE_NOT_RESTARTABLE` | The last execution is `ABANDONED`, or `FAILED`/`STOPPED` and the job does not allow restart. | the last execution |
| `409 BATCH_WINDOW_REQUIRED` | The job needs a window (section 6) and no window is open for exactly this `cycleId` or `windowRunKey`. This is a DAG wiring error, not a transient state. | none |
| `400 INVALID_JOB_PARAMETERS` | A missing or malformed param, an unknown `cycleId`, `runId` or `exportId`, a `businessDate` that does not match, or a changed non-identifying param on restart. | none |
| `404 JOB_NOT_FOUND` | Unknown `jobName`. | none |

### 2.3 Outputs

Output folders are `<kind>/<id>`, where the ID is generated by batch-service: the `batch.report_run` ID for the transaction report, `cycleId`-`attempt` for statements, and the Spring Batch job instance ID for the listing. `exportMastersJob` creates a `batch.export_run` row with a generated `exportId` (`BIGINT`). The export writes to `exports/<exportId>`, and the poll returns `"outputs": {"exportId": 42}` once the job completes. `importMigrationJob` takes that `exportId`. The import refuses an export that is not `COMPLETED` with `400` (`field: exportId`).

## 3. Poll: `GET /internal/jobs/executions/{executionId}`

`200`:

```json
{"executionId": 123, "jobName": "postDailyTransactionsJob", "cycleId": 7, "runId": null, "runKey": null,
 "executionStatus": "STARTED", "exitStatus": "EXECUTING", "rejectCount": 0,
 "startTime": "...", "endTime": null,
 "lease": {"state": "LIVE", "renewedAt": "...", "expiresAt": "...", "recoverableAt": "...", "recoverable": false, "dbTime": "..."},
 "outputs": {},
 "steps": [{"name": "post", "status": "STARTED", "readCount": 120, "writeCount": 118, "skipCount": 0}]}
```

- **The poll never recovers and never writes.** It reports the lease as seen by the database clock: `state` is `LIVE`, `EXPIRED` (not renewed for the TTL), `REVOKED` or `NONE`, and `recoverable` is true once the execution can be recovered (section 4.1). All times are database times, so the caller compares nothing with its own clock.
- `404 EXECUTION_NOT_FOUND` for an unknown ID.

## 4. Execution ownership, fencing and recovery

Requirements: an orphaned execution is not polled forever; a live owner's execution is never failed; the old owner is revoked atomically before the execution is failed; expiry is checked in the fence; every mutating path is fenced; an owner that was paused or suspended and resumes after a takeover can change nothing.

### 4.1 Lease

- A V4xx migration adds `batch.execution_lease` (`job_execution_id BIGINT` PK FK to `BATCH_JOB_EXECUTION`, `owner_id UNIQUEIDENTIFIER`, `owner_host VARCHAR(255)`, `owner_pid INT`, `acquired_at`, `renewed_at`, `revoked_at` NULL `DATETIME2(3)`, `revoked_reason VARCHAR(100)` NULL). The process that creates or restarts an execution inserts the row in the same transaction. A restart is a new execution with a new row; revoked rows stay as the record of the takeover. `owner_id` is random per process.
- Settings, with defaults: renewal interval 10 s; TTL 60 s; grace 60 s. The TTL must be at least 3 times the interval, and the grace at least the TTL.
- **Expired**, for reporting only: `renewed_at < SYSUTCDATETIME() - TTL`.
- **Recoverable**: `revoked_at IS NULL AND renewed_at < SYSUTCDATETIME() - (TTL + grace)` (120 s by default). The fence, the renewal and the recovery all use this one predicate, so the three cannot disagree. An owner that resumes after a pause shorter than TTL + grace, and before anyone recovered it, keeps its lease.
- Only the database clock (`SYSUTCDATETIME()`) is compared. Host clocks, the JVM's monotonic clock and Airflow's clock never decide liveness.

### 4.2 Renewal

The owner's scheduler thread runs `UPDATE batch.execution_lease SET renewed_at = SYSUTCDATETIME() WHERE job_execution_id = @id AND owner_id = @me AND NOT <recoverable> AND revoked_at IS NULL`. When it updates 0 rows, the lease is lost for good: the execution is marked lost in memory, the step stops at its next transaction boundary, and the process writes nothing more for it, including Spring Batch status updates (4.5).

### 4.3 Fence

- Every transaction that changes data on behalf of an execution runs the renewal UPDATE as its last statement before COMMIT. 0 rows: the transaction rolls back and the step fails with `LEASE_LOST`.
- The fence's exclusive lock on the lease row is held until the commit, so a revocation cannot fall between fence and commit.
- **Two transaction managers.** Job steps get only the fencing transaction manager: a decorator that registers the fence as a `beforeCommit` synchronization for the execution bound to the thread, and fails a commit with no bound execution. Request handlers (cycle registration, window open and close, report-run reads) use the plain one and hold no lease; window operations are serialized by the window's application lock. A test asserts that every step of every registered job runs through the fencing decorator, and that no job bean can obtain the plain one.
- **Coverage** includes every chunk and every tasklet: `archive`, `claimOnlineAdds`, `stageAuthorizationMatches`, `applyAuthorizationMatches`, `apply_retention`, the `dataLoadJob` steps, `checkDefaultGroup`, the purge, the import, the exports, the report and the listing, plus the status writes on `batch.report_run` and `batch.export_run`.
- **Files.** File operations never happen between the fence and the commit. An output folder is written under a temporary name and renamed to `<kind>/<id>` after the commit. The rename is idempotent: a restart replaces a leftover temporary folder and an existing target. The inbound daily file is identified at `stage` start by its name and SHA-256, stored with the cycle. It is moved to the archive after the job's last commit. The reader accepts the file in either place when the checksum matches, so a crash between commit and move loses nothing.

### 4.4 Recovery (revoke, then fail, in one transaction)

Only a **launch** recovers (section 2), and only an execution that is non-terminal and recoverable. Polls, startup and bulk sweeps never recover. One transaction with `SET LOCK_TIMEOUT 5000`:

1. `UPDATE batch.execution_lease SET revoked_at = SYSUTCDATETIME(), revoked_reason = 'OWNER_LOST' WHERE job_execution_id = @id AND <recoverable>`. 0 rows: the owner renewed, or another launch recovered first. Answer from the current state. Lock timeout: the owner is between fence and commit. Answer `409 JOB_EXECUTION_RUNNING`.
2. Mark the job execution and its non-terminal step executions `FAILED`, exit description `OWNER_LOST: lease not renewed since <renewedAt>, owner <host>/<pid>`, `end_time`, `VERSION` incremented.
3. Commit, then start the restart, which answers `202 restart: true`.

An execution with **no lease row** is recoverable once its `BATCH_JOB_EXECUTION.LAST_UPDATED` is older than TTL + grace. Recovery first inserts a revoked lease row for it, so the same row lock serializes recoverers.

### 4.5 A paused owner that resumes

Its next fence or renewal updates 0 rows, so its chunk rolls back and the execution is marked lost. Its Spring Batch repository writes fail the optimistic `VERSION` check, and a repository decorator refuses any write for an execution marked lost. It never overwrites the recovered `FAILED` status or the restart's rows. Rows it had locked in an uncommitted transaction stay locked until its connection ends. The restart waits for them up to its lock timeout, then fails, and the task retries. The task's `execution_timeout` bounds the one case that recovery cannot resolve: an owner paused between fence and commit.

### 4.6 Suspend and pause behaviour

A laptop that sleeps suspends Airflow, batch-service and the SQL Server container together. On resume, the database clock jumps by the sleep time, and the JVM's scheduler may fire its next renewal up to one interval later. After a sleep longer than TTL + grace, a launch that runs before that renewal can recover a live owner. Fencing keeps this safe: the owner's next commit fails, and the restart resumes after the last committed chunk. The cost is one restart, never a double apply. This behaviour is verified, not assumed:

- In CI (Linux): `SIGSTOP` the batch-service JVM for less than TTL + grace, then `SIGCONT`. Expected: no recovery, and the job completes. Repeat for longer than TTL + grace with a launch during the stop. Expected: one recovery, the owner's next fence fails, and the results equal an uninterrupted run.
- Once on the macOS dev host, recorded in `verify-cycle`'s notes: put the host to sleep (`pmset sleepnow`) during `post` for 30 s and again for 5 minutes. Record how the container's `SYSUTCDATETIME()` and the JVM renewal behaved, and whether a recovery happened. If the database clock does not jump on this platform, record that too. Either way, the result must equal an uninterrupted run.

### 4.7 Tests (batch-service `ExecutionLeaseTest` and the job tests, against SQL Server, with interval 0.5 s, TTL 2 s and grace 2 s)

The r3 table stands, with these changes. The polls in "crash" show `lease.state` going `LIVE`, then `EXPIRED`, then `recoverable: true`, and never change the execution. The recovery in "expired, live owner" is done by a launch. Added cases:

- A pause shorter than TTL + grace: no recovery.
- Lease-less recovery uses TTL + grace.
- A restart with a changed non-identifying param gets `400`.
- The import refuses a non-`COMPLETED` export.
- The file move: a crash between the last commit and the move, then a restart, gives the same result.

## 5. Operator rules (`SpringBatchJobOperator`)

| Answer | Operator action |
| :-- | :-- |
| `202` | Defer to the poll trigger with `executionId` (every 10 s). |
| `409 JOB_EXECUTION_RUNNING` | Attach to `executionId` and defer as for `202`. |
| `409 JOB_INSTANCE_COMPLETE`, `exitStatus` `COMPLETED` or `COMPLETED_WITH_REJECTS` | Succeed without relaunching; push `rejectCount` and `outputs` as after a poll. |
| `409 JOB_INSTANCE_COMPLETE`, any other `exitStatus` | Fail, not retryable. |
| `409 JOB_INSTANCE_NOT_RESTARTABLE`, `409 BATCH_WINDOW_REQUIRED`, `400`, `401`, `404` | Fail, not retryable (`AirflowFailException`). |
| `503 WINDOW_BUSY` (window open only) | Wait for `Retry-After` and try again, deferred, until the task's `execution_timeout`. These waits do not use task retries. |
| any other `5xx`, connection refused, timeout | Retryable through Airflow task retries; the same POST is idempotent. |

Poll results:

- `COMPLETED` with exit `COMPLETED` or `COMPLETED_WITH_REJECTS` is success. `rejectCount` and `outputs` go to XCom, and the reject count goes to the task log. A DAG run note is optional. The operator needs no Airflow API credentials for it.
- Any other terminal result is a task failure.
- Non-terminal with `lease.recoverable: true` fails the attempt as retryable. The task retry re-POSTs the launch, which recovers and restarts. Every operator task therefore has at least 2 retries.

Clearing a task never reruns a completed job: its identity is unchanged, so it gets `JOB_INSTANCE_COMPLETE` and succeeds. A deliberate rerun of a run-keyed job is a new DAG run. A rerun of a cycle is `cycle-rerun` (section 7). Clearing a failed task (for example the purge after the close) restarts that job.

## 6. Batch window

There is one global `WRITES_BLOCKED` window. It is the serialization point between the daily cycle, the reference refresh, the data load and the import. The Airflow pool `carddemo_batch` limits concurrent tasks; it does not keep two DAGs' windows apart.

- **Holders.** A cycle window is opened with `{cycleId}`. Any other window is opened with `{windowRunKey}` = `"<dag_id>/<run_id>"` (the CLI: `"cli/<host>/<timestamp>/<UUID>"`). A V4xx migration adds `ops.batch_window.run_key VARCHAR(250) NULL`, with a check that exactly one of `cycle_id` and `run_key` is set.
- **Open**: `POST /internal/batch-window/open {cycleId | windowRunKey}` returns `200 {windowId, cycleId, windowRunKey, mode: "WRITES_BLOCKED", openedAt}`.
  - It is idempotent for the same holder.
  - While another holder's window is open: `503 WINDOW_BUSY` with `Retry-After` (default 60 s) and the open window's members.
  - Writes not drained in time: `503 WINDOW_DRAIN_TIMEOUT`, with the window left closed (retryable).
  - A failed cycle leaves its window open (ERR-JCA7-001). Other DAGs then wait until their `execution_timeout`, which is the signal to the operator.
- **Close**: `POST /internal/batch-window/close {cycleId | windowRunKey, complete}` returns `200 {windowId, cycleId, windowRunKey, closedAt, cycle}` (`cycle` is null for a run-key window).
  - `complete: true` is sent only by the DAG's `close_batch_window`, after every earlier task succeeded. It also sets the cycle `COMPLETED`.
  - `complete: false` is sent by `make -C modernization batch-window-close` after a failure. It reopens online writes and leaves the cycle `OPEN`, so the next DAG run resumes it.
  - Closing while an execution of that holder is non-terminal and not recoverable gets `409 EXECUTIONS_RUNNING` with their IDs. Closing an already closed window returns the same body.
- **Which jobs need a window**: every cycle job except `purgePendingAuthorizationsJob` (it writes only `pauth`, which is not covered), plus `dataLoadJob`, `disclosureGroupReloadJob`, `transactionTypeMaintenanceJob` and `importMigrationJob`.
  - A cycle job needs the window held by exactly its `cycleId`. A run-keyed job needs the window held by exactly its `windowRunKey` param.
  - There is no prefix match and no "any open window".
  - `exportMastersJob`, `categoryBalanceListingJob` and `transactionReportJob` need none.
  - `carddemo_import_migration` gets `open_batch_window` and `close_batch_window` tasks, and `make load` opens and closes a CLI window.
- The purge may run after the cycle is `COMPLETED`, identified by `cycleId` and `attempt`.

## 7. Deliberate cycle rerun

`make -C modernization cycle-rerun CYCLE=<id>` refuses while any execution of that cycle is non-terminal and not recoverable. Otherwise it opens the cycle's window and recovers any recoverable executions. It then reverts the cycle's authorization matches ([d33-matching-brief.md](d33-matching-brief.md#batch-steps)), does the restore that [batch.md](batch.md#restart-and-idempotency) describes, raises `attempt` and sets the cycle `OPEN`. The next DAG run therefore creates fresh job instances (`attempt` is identifying).

## 8. Superseded rules

The single-instance application lock `carddemo.batch-service` and the startup sweep that marked `STARTED` executions `ORPHANED` (arch commit cba382f) are withdrawn. Several batch-service processes may run against one database: each process recovers only through a launch, and the fence stops a stale owner.
