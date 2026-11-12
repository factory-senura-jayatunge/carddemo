"""The context decoder against streams Spring Batch 6.0.6 wrote (SpringBatchContexts.java)."""
from __future__ import annotations

import base64
import gzip
import hashlib
import json
import random
import re
import struct
import subprocess
import sys
import unittest
from pathlib import Path

from modernization.verify.adapter import javaser

FIXTURE = Path(__file__).with_name("spring_batch_contexts.json.gz")
CASES = json.loads(gzip.decompress(FIXTURE.read_bytes()))
INTEGRAL = ("java.lang.Long", "java.lang.Integer")


def raw(case: str) -> bytes:
    return base64.b64decode(CASES[case]["stored"])


class JavaFixtureTest(unittest.TestCase):
    def test_every_case_decodes_to_what_java_held(self):
        self.assertEqual(set(CASES), {"mixed", "empty", "linked", "refs", "opaque", "longstring"})
        for case, data in CASES.items():
            with self.subTest(case=case):
                decoded = javaser.decode(data["stored"])
                self.assertEqual(set(decoded), set(data["expect"]))
                for key, (cls, text) in data["expect"].items():
                    value = decoded[key]
                    if cls == "java.lang.String":
                        self.assertIsInstance(value, str)
                        if text.startswith("sha256:"):
                            self.assertEqual("sha256:" + hashlib.sha256(value.encode()).hexdigest(), text)
                        else:
                            self.assertEqual(value, text)
                    elif cls in INTEGRAL:
                        self.assertIs(type(value), int)
                        self.assertEqual(str(value), text)
                    elif cls == "java.lang.Boolean":
                        self.assertIs(type(value), bool)
                        self.assertEqual(str(value).lower(), text)
                    else:
                        self.assertIsInstance(value, javaser.Opaque)
                        self.assertEqual(value.class_name, cls)

    def test_opaque_values_equal_no_plain_value(self):
        decoded = javaser.decode(CASES["opaque"]["stored"])
        for key in ("amount", "when", "ratio", "nested"):
            self.assertNotIn(decoded[key], ("1.50", 1.5, 0.5, 0, None, {"k": 1}))
        self.assertEqual(decoded["count"], 5)

    def test_an_opaque_value_equals_only_itself(self):
        first, second = (javaser.decode(CASES["opaque"]["stored"]) for _ in range(2))
        for key in ("amount", "when", "ratio", "nested"):
            with self.subTest(key=key):
                self.assertEqual(first[key], first[key])
                self.assertNotEqual(first[key], second[key])
        self.assertNotEqual(javaser.Opaque("java.util.Date"), javaser.Opaque("java.util.Date"))

    def test_long_string_uses_the_long_form(self):
        self.assertIn(bytes([javaser.TC_LONGSTRING]), raw("longstring"))
        self.assertEqual(len(javaser.decode(CASES["longstring"]["stored"])["long"]), 70000)

    def test_back_references_resolve(self):
        decoded = javaser.decode(CASES["refs"]["stored"])
        self.assertEqual((decoded["a"], decoded["b"], decoded["c"], decoded["d"]),
                         ("shared-value", "shared-value", 7000, 7000))


class MalformedStreamTest(unittest.TestCase):
    def assertRefused(self, function, *args):
        with self.assertRaises(javaser.ContextError) as caught:
            function(*args)
        self.assertRegex(str(caught.exception), r"\ACONTEXT_[A-Z0-9_]+\Z")

    def test_every_truncation_is_refused(self):
        for case in ("mixed", "refs", "opaque", "linked"):
            data = raw(case)
            for cut in range(len(data)):
                with self.subTest(case=case, cut=cut):
                    self.assertRefused(javaser.decode_stream, data[:cut])

    def test_random_corruption_only_ever_raises_context_error(self):
        rng = random.Random(20261111)
        streams = [raw(c) for c in CASES]
        for _ in range(4000):
            data = bytearray(rng.choice(streams))
            for _ in range(rng.randint(1, 3)):
                data[rng.randrange(len(data))] = rng.randrange(256)
            try:
                result = javaser.decode_stream(bytes(data))
            except javaser.ContextError as exc:
                self.assertRegex(str(exc), r"\ACONTEXT_[A-Z0-9_]+\Z")
            else:
                self.assertIsInstance(result, dict)

    def test_trailing_bytes_bad_magic_and_base64(self):
        self.assertRefused(javaser.decode_stream, raw("mixed") + b"\x00")
        self.assertRefused(javaser.decode_stream, b"\xac\xed\x00\x06" + raw("mixed")[4:])
        self.assertRefused(javaser.decode, "not base64!")
        self.assertRefused(javaser.decode, "\u00e9" + CASES["empty"]["stored"])
        self.assertRefused(javaser.decode, b"x")

    def test_top_level_must_be_a_map(self):
        self.assertRefused(javaser.decode_stream, javaser.MAGIC + b"\x74\x00\x01a")
        self.assertRefused(javaser.decode_stream, javaser.MAGIC + b"\x70")

    def test_duplicate_key_is_refused(self):
        data = raw("refs")
        self.assertEqual(data.count(b"\x74\x00\x01b"), 1)
        self.assertRefused(javaser.decode_stream, data.replace(b"\x74\x00\x01b", b"\x74\x00\x01a"))

    def test_map_size_must_match_its_entries(self):
        data = raw("linked")
        start = data.index(b"\x77\x08") + 2
        self.assertEqual(data[start + 4:start + 8], b"\x00\x00\x00\x02")
        for size in (b"\x00\x00\x00\x01", b"\x00\x00\x00\x03", b"\xff\xff\xff\xff"):
            with self.subTest(size=size):
                self.assertRefused(javaser.decode_stream, data[:start + 4] + size + data[start + 8:])

    def test_error_messages_carry_no_content(self):
        data = raw("mixed")
        for cut in (10, 60, len(data) - 1):
            try:
                javaser.decode_stream(data[:cut])
            except javaser.ContextError as exc:
                self.assertNotIn("accounts", str(exc))
                self.assertTrue(re.fullmatch(r"CONTEXT_[A-Z0-9_]+", str(exc)))


def class_desc(name: bytes, superclass: bytes) -> bytes:
    """TC_CLASSDESC of a serializable class with no fields, then its superclass."""
    return (bytes([javaser.TC_CLASSDESC]) + struct.pack(">H", len(name)) + name + bytes(8)
            + bytes([javaser.SC_SERIALIZABLE]) + struct.pack(">h", 0) + bytes([javaser.TC_ENDBLOCKDATA]) + superclass)


def reference(handle: int) -> bytes:
    return bytes([javaser.TC_REFERENCE]) + struct.pack(">i", javaser.BASE_HANDLE + handle)


# A new object whose class descriptor names itself (its own handle) as its superclass.
SELF_SUPERCLASS = javaser.MAGIC + bytes([javaser.TC_OBJECT]) + class_desc(b"X", reference(0))


class DescriptorCycleTest(unittest.TestCase):
    def assertDescriptorRefused(self, data: bytes):
        with self.assertRaises(javaser.ContextError) as caught:
            javaser.decode_stream(data)
        self.assertEqual(str(caught.exception), "CONTEXT_CLASS_DESCRIPTOR")

    def test_a_class_that_is_its_own_superclass_is_refused(self):
        self.assertEqual(len(SELF_SUPERCLASS), 26)
        self.assertEqual(base64.b64encode(SELF_SUPERCLASS), b"rO0ABXNyAAFYAAAAAAAAAAACAAB4cQB+AAA=")
        self.assertDescriptorRefused(SELF_SUPERCLASS)
        with self.assertRaisesRegex(javaser.ContextError, r"\ACONTEXT_CLASS_DESCRIPTOR\Z"):
            javaser.decode("rO0ABXNyAAFYAAAAAAAAAAACAAB4cQB+AAA=")

    def test_a_superclass_cycle_through_another_descriptor_is_refused(self):
        # A (handle 0) has the new B (handle 1) as superclass, and B names A.
        self.assertDescriptorRefused(javaser.MAGIC + bytes([javaser.TC_OBJECT])
                                     + class_desc(b"A", class_desc(b"B", reference(0))))

    def test_the_cycle_is_refused_within_a_time_bound_in_a_fresh_process(self):
        code = ("import sys\nfrom modernization.verify.adapter import javaser\n"
                "try:\n    javaser.decode_stream(bytes.fromhex(sys.argv[1]))\n"
                "except javaser.ContextError as e:\n    print(e)\n")
        done = subprocess.run([sys.executable, "-c", code, SELF_SUPERCLASS.hex()], cwd=Path(__file__).resolve().parents[4],
                              capture_output=True, text=True, timeout=60)
        self.assertEqual((done.returncode, done.stdout), (0, "CONTEXT_CLASS_DESCRIPTOR\n"))

    def test_a_superclass_chain_is_bounded_by_max_depth(self):
        for length, refused in ((javaser.MAX_DEPTH, False), (javaser.MAX_DEPTH + 1, True)):
            chain = []
            for i in range(length):
                chain.append(javaser._ClassDesc(f"C{i}", javaser.SC_SERIALIZABLE, [], chain[-1] if chain else None))
            reader = javaser._Reader(class_desc(b"Z", reference(length - 1)))
            reader.handles = list(chain)
            with self.subTest(length=length):
                if refused:
                    with self.assertRaisesRegex(javaser.ContextError, r"\ACONTEXT_CLASS_DESCRIPTOR\Z"):
                        reader.content(0)
                else:
                    self.assertIs(reader.content(0).superclass, chain[-1])


class StoredTextTest(unittest.TestCase):
    def test_serialized_context_wins_and_short_is_the_fallback(self):
        full = CASES["longstring"]["stored"]
        short = full[:2492] + " ..."
        self.assertEqual(javaser.stored_text(short, full), full)
        self.assertEqual(javaser.stored_text(CASES["empty"]["stored"], None), CASES["empty"]["stored"])
        with self.assertRaises(javaser.ContextError):
            javaser.stored_text(None, None)
        with self.assertRaises(javaser.ContextError):
            javaser.decode(short)


if __name__ == "__main__":
    unittest.main()
