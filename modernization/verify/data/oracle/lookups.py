"""Read the account-update spec's lookup source, never loader constants."""
import re

from modernization.verify.spec_jobs.common import InvalidFixture


def parse(source: str) -> dict[str, list[dict]]:
    # Legacy copybook uses tabs in its code area. Strip comments, not columns
    # 8-72, since tabs do not represent actual fixed character offsets here.
    clean = "\n".join(line for line in source.splitlines() if not line.startswith("      *"))
    blocks = {}
    for name in ("VALID-GENERAL-PURP-CODE", "VALID-US-STATE-CODE", "VALID-US-STATE-ZIP-CD2-COMBO"):
        match = re.search(r"\b88\s+" + name + r"\s+VALUES?\s+(.*?)\.", clean, re.S)
        if not match:
            raise InvalidFixture("LOOKUP_SOURCE")
        blocks[name] = re.findall(r"'([^']*)'", match.group(1))
    return {
        "ref.us_state": [{"state_cd": s} for s in blocks["VALID-US-STATE-CODE"]],
        "ref.us_state_zip_prefix": [{"state_cd": s[:2], "zip_prefix": s[2:]} for s in blocks["VALID-US-STATE-ZIP-CD2-COMBO"]],
        "ref.nanp_area_code": [{"area_code": s} for s in blocks["VALID-GENERAL-PURP-CODE"]],
    }
