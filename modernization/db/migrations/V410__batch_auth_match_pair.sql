-- D-33 matching (decided 2026-11-10, option 1; d33-matching-brief.md): the
-- pairs of one pending approval and one transaction posted by the cycle that
-- postDailyTransactionsJob's stageAuthorizationMatches found unique on both
-- sides, for one cycle attempt. applyAuthorizationMatches releases them in
-- auth_id order; a restart resumes after its last committed chunk, and a pair
-- applied already no longer finds its approval pending. cycle-rerun deletes the
-- cycle's rows. No foreign keys: the purge may delete the approval, retention
-- the transaction, and the test fixtures delete batch.cycle rows on their own.
CREATE TABLE batch.auth_match_pair (
    cycle_id  INT     NOT NULL,
    attempt   INT     NOT NULL,
    auth_id   BIGINT  NOT NULL,
    tran_pk   BIGINT  NOT NULL,
    CONSTRAINT PK_auth_match_pair PRIMARY KEY (cycle_id, attempt, auth_id, tran_pk)
);
GO
