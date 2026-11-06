-- The batch window replaces CICS CLOSEFIL/OPENFIL (INV-002). An open window is
-- a row with closed_at NULL; at most one can be open. Online writers hold the
-- application lock 'carddemo.batch_window' shared for each write transaction;
-- opening a window takes it exclusively, so it waits for writes in flight.
-- cycle_id gets its foreign key when the batch range creates batch.cycle.
CREATE TABLE ops.batch_window (
    window_id  INT IDENTITY(1,1) NOT NULL,
    cycle_id   INT               NULL,
    mode       VARCHAR(16)       NOT NULL,
    scope      CHAR(1)           NOT NULL CONSTRAINT DF_batch_window_scope DEFAULT 'G',
    opened_at  DATETIME2(3)      NOT NULL CONSTRAINT DF_batch_window_opened_at DEFAULT SYSUTCDATETIME(),
    closed_at  DATETIME2(3)      NULL,
    opened_by  VARCHAR(40)       NULL,
    CONSTRAINT PK_batch_window PRIMARY KEY (window_id),
    CONSTRAINT CK_batch_window_mode CHECK (mode IN ('WRITES_BLOCKED')),
    CONSTRAINT CK_batch_window_scope CHECK (scope = 'G'),
    CONSTRAINT CK_batch_window_closed CHECK (closed_at IS NULL OR closed_at >= opened_at)
);
GO
CREATE UNIQUE INDEX UX_batch_window_open ON ops.batch_window (scope) WHERE closed_at IS NULL;
GO
