# CardDemo runbooks

Operational procedures for the CardDemo batch cycle and its CICS files. Each runbook is
based on the JCL in `app/jcl` and the programs it runs. Dataset names use the shipped
high-level qualifier `AWS.M2.CARDDEMO`; change it if you installed under another HLQ.

| Runbook | Use it when |
| :------ | :---------- |
| [Batch cycle failure and restart](batch-cycle-restart.md) | A job in the POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, TRANIDX sequence fails |
| [VSAM files left open by CICS](cics-vsam-files.md) | Batch jobs cannot open or delete a VSAM file, or online transactions fail after batch |
| [Daily transaction rejects](daily-rejects.md) | POSTTRAN ends with return code 4, or `DALYREJS` needs reviewing |
| [Expired authorization purge](authorization-purge.md) | Running or recovering `CBPAUP0J` in the optional IMS/Db2/MQ module |

## Common facts

- **Abend codes.** The base batch programs end through `9999-ABEND-PROGRAM`, which calls
  `CEE3ABD` with code 999. The job log shows user abend `U0999`. The program writes the
  failing operation (for example `ERROR OPENING ACCOUNT MASTER FILE`) and
  `FILE STATUS IS: NNNN` followed by the VSAM file status just before it.
- **No checkpoint/restart.** `CBTRN02C` (POSTTRAN) and `CBACT04C` (INTCALC) update VSAM files
  in place and take no checkpoints. A failure part way through leaves some records updated.
  Restart means restoring the files and running the job again, never resubmitting on top of
  partial updates.
- **GDG generations.** `DEFGDGB` and `DALYREJS` define the GDG bases with `LIMIT(5) SCRATCH`.
  A generation is cataloged when its step ends without an abend, even with a non-zero return
  code, so check return codes before trusting `(0)`.
- **CICS region.** `CLOSEFIL`, `OPENFIL` and the file refresh jobs send CEMT commands to a
  region named `CICSAWSA` through SDSF. Change that name to your region.
- **Schedulers.** `app/scheduler/CardDemo.ca7` and `app/scheduler/CardDemo.controlm` hold
  the same jobs as CA7 and Control-M definitions; the Control-M file groups them into daily
  transaction backup, weekly refresh and monthly interest calculation folders.
