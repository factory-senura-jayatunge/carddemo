# COBSWAIT probe

Not an equivalence check (COBSWAIT only waits), so `verify-spec` does not run it. `waitprob.cbl` has the same data items and the same ACCEPT and MOVE as `app/cbl/COBSWAIT.cbl`, but displays the binary value it would pass to MVSWAIT instead of waiting.

```sh
python3 docs/modernization/spec/checks/cobswait_probe/run_probe.py
```

It compiles the probe with the project's cobc flags, feeds it each SYSIN value of the table in `run_probe.py` and exits 0 when every value matches. The COBSWAIT spec (`programs/COBSWAIT.md`) quotes the results.
