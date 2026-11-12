-- The database instance marker (batch-execution-contract.md section 4.10,
-- data-model.md ops.database_instance). One row, created with this migration,
-- whose instance_id names this database instance. Every output root is
-- derived from DB_NAME() and this ID, so a dropped and recreated database,
-- which hands out run and execution IDs again from 1, never meets an earlier
-- instance's files. Every way of creating a database runs the migrations, so
-- every new database gets a new ID here.
--
-- Only db-migrate, as the database owner, changes the row afterwards
-- (--new-instance after a restore). The service logins get SELECT and nothing
-- else, in their R__grants files.

CREATE TABLE ops.database_instance (
    id           TINYINT           NOT NULL CONSTRAINT PK_database_instance PRIMARY KEY
                                            CONSTRAINT CK_database_instance_id CHECK (id = 1),
    instance_id  UNIQUEIDENTIFIER  NOT NULL,
    created_at   DATETIME2(3)      NOT NULL CONSTRAINT DF_database_instance_created_at DEFAULT SYSUTCDATETIME()
);
GO

INSERT INTO ops.database_instance (id, instance_id) VALUES (1, NEWID());
GO
