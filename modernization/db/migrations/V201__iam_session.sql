-- One row per signed-on session (D-ARCH-11). Only SHA-256 hashes of the
-- refresh tokens are stored: the current one and the one it replaced, so a
-- replayed old token is recognised (REUSE_DETECTED).
CREATE TABLE iam.session (
    session_id            UNIQUEIDENTIFIER NOT NULL,
    user_id               CHAR(8)          COLLATE Latin1_General_100_BIN2 NOT NULL,
    created_at            DATETIME2(3)     NOT NULL,
    last_activity_at      DATETIME2(3)     NOT NULL,
    absolute_expiry_at    DATETIME2(3)     NOT NULL,
    ended_at              DATETIME2(3)     NULL,
    end_reason            VARCHAR(20)      NULL,
    refresh_hash          BINARY(32)       NOT NULL,
    previous_refresh_hash BINARY(32)       NULL,
    CONSTRAINT PK_session PRIMARY KEY (session_id),
    CONSTRAINT FK_session_user FOREIGN KEY (user_id) REFERENCES iam.app_user (user_id),
    CONSTRAINT CK_session_end_reason CHECK (end_reason IN
        ('SIGN_OFF', 'IDLE', 'EXPIRED', 'REUSE_DETECTED', 'PASSWORD_CHANGE', 'USER_REFUSED')),
    CONSTRAINT CK_session_ended CHECK ((ended_at IS NULL AND end_reason IS NULL)
                                    OR (ended_at IS NOT NULL AND end_reason IS NOT NULL)),
    CONSTRAINT CK_session_expiry CHECK (absolute_expiry_at > created_at)
);
GO

CREATE UNIQUE INDEX UX_session_refresh ON iam.session (refresh_hash);
GO
CREATE UNIQUE INDEX UX_session_previous_refresh ON iam.session (previous_refresh_hash)
    WHERE previous_refresh_hash IS NOT NULL;
GO
CREATE INDEX IX_session_user ON iam.session (user_id, ended_at);
GO
