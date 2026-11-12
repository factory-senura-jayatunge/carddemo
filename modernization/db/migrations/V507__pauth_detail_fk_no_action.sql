-- A summary is deleted only after its details (data-model.md, authorization-
-- service.md section 3). With ON DELETE CASCADE every summary delete also
-- ran a serializable seek of auth_detail for the account, and that seek
-- takes a range lock on the first key of the next account in
-- UQ_auth_detail_9c even when the account has no detail left. A purge then
-- waits on, or deadlocks with, a session that holds the next account's
-- newest detail. The purge, the loader and the data load already delete the
-- details first, so the cascade never removes a row; NO ACTION makes a
-- forgotten detail fail the delete instead. Same name and columns, checked
-- against the existing rows, which the purge and load rules already keep
-- consistent; no row changes.
ALTER TABLE pauth.auth_detail DROP CONSTRAINT FK_auth_detail_summary;
GO
ALTER TABLE pauth.auth_detail WITH CHECK ADD CONSTRAINT FK_auth_detail_summary
    FOREIGN KEY (acct_id) REFERENCES pauth.auth_summary (acct_id) ON DELETE NO ACTION ON UPDATE NO ACTION;
GO
