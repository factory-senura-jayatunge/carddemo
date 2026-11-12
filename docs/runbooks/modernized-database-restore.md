# Restoring the modernized database

This runbook is for the modernized stack in `modernization/`, not for the mainframe. Use it
when the application database (`CARDDEMO_DB_NAME`, default `carddemo`) is put back from a
backup or a copy: `RESTORE ... WITH REPLACE`, an attached copy of the files, or a copied
volume. It also says what to do when a batch job, a report download or db-migrate stops with
`OUTPUT_ROOT_INCOMPLETE` or `OUTPUT_ROOT_MISMATCH`.

## Why a restore needs a step of its own

Every batch output is under the output root of its database,
`$CARDDEMO_VAR_DIR/out/<database component>/<instance ID>/` (section 4.10 of
[the batch execution contract](../modernization/architecture/batch-execution-contract.md)).
The instance ID is a row of the database itself (`ops.database_instance`), and the root's
`.instance` file holds the same identity.

On SQL Server a restore over the same database keeps the database's creation date and GUID.
It also rolls identities and sequences back, so the restored database issues again every run
ID and job execution ID that was issued after the backup. Nothing in the database shows that
it was restored, and the restored instance ID still names the old root.

Section 4.8 of the contract is meant to make integrity independent of this runbook: each
writer publishes with create-new, so a reissued run ID that finds its file already there
fails with `PUBLICATION_MISMATCH` instead of writing over it. Not every writer does that yet.
Until they do, the restored database's reissued run IDs and execution IDs can write over the
old root's files of the same IDs (the daily reject file of a reissued cycle attempt, for
example), so follow step 3 before any batch run after a restore. Downloads are not affected:
transaction-service serves only completed report runs, and it only reads.

## Rules

- Never delete, move or edit an output root, an old root or anything under `out/` to get a
  run through. The only exception is the incomplete folder in
  [OUTPUT_ROOT_INCOMPLETE](#output_root_incomplete), which you move aside, never delete.
  Removing an old root is a retention decision (D-BAT-04), taken separately.
- Run step 3 only after a restore, an attach or a copy. On a database that was not put back,
  `NEW_INSTANCE=1` only takes away the reports of its earlier runs (they are no longer
  served).
- Do not run `docker inspect`, `env` or `ps e` against the containers, and do not put the SA
  password on a command line. The commands below read it from the container's secret.

## Steps

1. Stop the services and Airflow. The containers keep running.

   ```sh
   make -C modernization stop
   ```

2. Restore the database with SQL Server's `RESTORE` (or attach the copy). The backup file
   has to be visible inside the `mssql` container, for example copied in with
   `docker compose cp`. Run from the main checkout's `modernization/`:

   ```sh
   docker compose --env-file .env exec -T mssql sh -c \
     'SQLCMDPASSWORD="$(cat /run/secrets/mssql_sa_password 2>/dev/null || printf %s "$MSSQL_SA_PASSWORD")" /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b \
      -Q "RESTORE DATABASE [carddemo] FROM DISK = N'"'"'/var/opt/mssql/backup/carddemo.bak'"'"' WITH REPLACE"'
   ```

   Use your database name and backup path. Use `MOVE` clauses if the files go elsewhere.

3. Give the restored database a new instance ID and its new output root:

   ```sh
   make -C modernization db-migrate NEW_INSTANCE=1
   ```

   db-migrate applies any migration the backup lacks and checks the logins' grants, as plain
   `make db-migrate` does. Then, as the database owner, it replaces the instance ID and creates
   the new root with its marker. It prints the new root and lists the old one as kept.
   `NEW_INSTANCE` takes only `1`; any other value fails before anything runs.

4. Start the stack again:

   ```sh
   make -C modernization up
   ```

## What to expect afterwards

- Outputs of the earlier instance stay in its root and are no longer read. A download of a
  report from before the restore gets the missing-file answer; run that report again.
- An execution that was started before the restore is bound to the old instance and fails
  with `OUTPUT_ROOT_MISMATCH` on restart. Rerun the cycle with
  `make -C modernization cycle-rerun CYCLE=<id>`, or start a new run.
- New runs write into the new root.

## OUTPUT_ROOT_INCOMPLETE

The root folder of the database's current instance exists but has no `.instance` marker. The
tools never create such a folder themselves: something outside them did, for example a copy
that stopped half way or a folder made by hand. It is reported and never repaired.

1. Stop the services and Airflow (`make -C modernization stop`).
2. Move the incomplete folder out of `out/` by hand, to a folder of your choice, for example
   `$CARDDEMO_VAR_DIR/aside/<date>/`. Keep it until somebody has looked at it. The error
   message and db-migrate's listing name the folder.
3. Create the root again with its marker:

   ```sh
   make -C modernization db-migrate
   ```

4. `make -C modernization up`.

## OUTPUT_ROOT_MISMATCH

The root's marker names another instance than the database, or a running execution's
database got a new instance ID meanwhile (step 3 above ran during the execution). Nothing is
written. Do not edit the marker. An execution bound to the old instance cannot finish: rerun
its cycle with `make -C modernization cycle-rerun CYCLE=<id>`, or start a new run. A marker that
names another database's instance means a folder was copied over this database's root; move
that folder aside as in [OUTPUT_ROOT_INCOMPLETE](#output_root_incomplete), then run
`make -C modernization db-migrate`.
