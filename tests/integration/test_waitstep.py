"""WAITSTEP (COBSWAIT + the MVSWAIT stand-in): SYSIN gives the wait in centiseconds."""
from __future__ import annotations

import dataclasses
import time

from tests.support import CardDemoTestCase, carddemo


class WaitStepTest(CardDemoTestCase):
    def wait(self, sysin: str) -> tuple[carddemo.StepResult, float]:
        job = dataclasses.replace(carddemo.JOBS["WAITSTEP"], sysin=sysin)
        work = self.new_work()
        start = time.monotonic()
        res = work.run_job(job)
        return res, time.monotonic() - start

    def test_waits_for_the_requested_centiseconds(self):
        res, seconds = self.wait("00000050\n")
        self.assert_rc(res, 0)
        self.assertGreaterEqual(seconds, 0.5)

    def test_zero_wait_returns_at_once(self):
        res, seconds = self.wait("00000000\n")
        self.assert_rc(res, 0)
        self.assertLess(seconds, 0.45)
