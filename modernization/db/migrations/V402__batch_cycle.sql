-- ENT-CYCLE: one row per batch-cycle run (batch.md, "One cycle date"). The
-- business date is the cycle's identity; Airflow's logical date or a conf
-- override. archived_count is what archiveMasterJob moved out of the master
-- (in_master = 0), the replacement of the TRANBKP backup.
CREATE TABLE batch.cycle (
    cycle_id        INT IDENTITY(1,1) NOT NULL,
    business_date   DATE              NOT NULL,
    -- INTCALC's PARM, yyyyMMdd + '00'; NULL on a cycle without interest.
    interest_parm   CHAR(10)          COLLATE Latin1_General_100_BIN2 NULL,
    status          VARCHAR(12)       NOT NULL CONSTRAINT DF_cycle_status DEFAULT 'OPEN',
    archived_count  INT               NULL,
    opened_at       DATETIME2(3)      NOT NULL CONSTRAINT DF_cycle_opened_at DEFAULT SYSUTCDATETIME(),
    closed_at       DATETIME2(3)      NULL,
    CONSTRAINT PK_cycle PRIMARY KEY (cycle_id),
    CONSTRAINT UQ_cycle_business_date UNIQUE (business_date),
    CONSTRAINT CK_cycle_status CHECK (status IN ('OPEN', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT CK_cycle_interest_parm CHECK (interest_parm IS NULL OR interest_parm LIKE '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]00'),
    CONSTRAINT CK_cycle_archived_count CHECK (archived_count IS NULL OR archived_count >= 0),
    CONSTRAINT CK_cycle_closed CHECK (closed_at IS NULL OR closed_at >= opened_at)
);
GO

-- The foundation range left these columns without a foreign key until this
-- table existed (V003, V014, V018).
ALTER TABLE core.[transaction] ADD CONSTRAINT FK_transaction_cycle
    FOREIGN KEY (cycle_id) REFERENCES batch.cycle (cycle_id);
GO
ALTER TABLE ops.batch_window ADD CONSTRAINT FK_batch_window_cycle
    FOREIGN KEY (cycle_id) REFERENCES batch.cycle (cycle_id);
GO
ALTER TABLE core.daily_tran ADD CONSTRAINT FK_daily_tran_cycle
    FOREIGN KEY (cycle_id) REFERENCES batch.cycle (cycle_id);
GO
ALTER TABLE core.daily_reject ADD CONSTRAINT FK_daily_reject_cycle
    FOREIGN KEY (cycle_id) REFERENCES batch.cycle (cycle_id);
GO
CREATE INDEX IX_tran_cycle ON core.[transaction] (cycle_id) WHERE cycle_id IS NOT NULL;
GO
