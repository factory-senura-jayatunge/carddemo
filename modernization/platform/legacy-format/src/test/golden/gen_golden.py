#!/usr/bin/env python3
"""Regenerate the GnuCOBOL golden file for the legacy-format codec tests.

The Java codecs are checked against what GnuCOBOL 3.2 itself produces with the
flags of the local build (tools/batch-programs.toml: -std=ibm -fsign=EBCDIC),
so the expectations do not come from the Java implementation. For every source
value and every target PICTURE the generated program MOVEs (and for signed
zoned targets also COMPUTEs) the value into the target and prints the target's
bytes: zoned and edited targets as text, packed and binary targets as hex.

Usage: python3 gen_golden.py [output]   (needs cobc on PATH)
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "..", "resources", "golden", "gnucobol-codecs.txt")

# name, PICTURE clause, kind: Z zoned text, H hex bytes, E edited text
TARGETS = [
    ("ZS10V2", "S9(10)V99", "Z"),
    ("ZS09V2", "S9(09)V99", "Z"),
    ("ZS04V2", "S9(04)V99", "Z"),
    ("ZS04", "S9(4)", "Z"),
    ("ZU11", "9(11)", "Z"),
    ("ZU09", "9(09)", "Z"),
    ("ZU03", "9(03)", "Z"),
    ("PS10V2", "S9(10)V99 COMP-3", "H"),
    ("PS09V2", "S9(09)V99 COMP-3", "H"),
    ("PS11", "S9(11) COMP-3", "H"),
    ("PS07", "S9(7) COMP-3", "H"),
    ("PU03", "9(03) COMP-3", "H"),
    ("PU08", "9(08) COMP-3", "H"),
    ("BU09", "9(09) COMP", "H"),
    ("BU11", "9(11) COMP", "H"),
    ("BU03", "9(03) COMP", "H"),
    ("BS10V2", "S9(10)V99 COMP", "H"),
    ("BS04", "S9(4) COMP", "H"),
    ("BS09", "S9(9) COMP", "H"),
    ("E01", "+ZZZ,ZZZ,ZZZ.99", "E"),
    ("E02", "+99999999.99", "E"),
    ("E03", "----9", "E"),
    ("E04", "+ZZZ,ZZZ,ZZZ.ZZ", "E"),
    ("E05", "-ZZZZZZZ9.99", "E"),
    ("E06", "Z(9).99-", "E"),
    ("E07", "+9(10).99", "E"),
    ("E08", "+9999999999.99", "E"),
    ("E09", "9(9).99-", "E"),
    ("E10", "-ZZZ,ZZZ,ZZZ.ZZ", "E"),
    ("E11", "+9(06)", "E"),
    ("E12", "+9(09)", "E"),
    ("E13", "-ZZZZZZZZZ9.99", "E"),
    ("E14", "-ZZZZ9.99", "E"),
    ("E15", "ZZZ,ZZZ,ZZ9.99CR", "E"),
    ("E16", "$$$,$$9.99DB", "E"),
    ("E17", "***,**9.99", "E"),
    ("E18", "++++9.99", "E"),
    ("E19", "99/99/99", "E"),
    ("E20", "ZZ9B99", "E"),
    ("E21", "Z,ZZZ.ZZ+", "E"),
    ("E22", "999.990", "E"),
]

# Edited targets are also filled from these fields, as the programs do: the
# source PICTURE changes the result when a value overflows the target.
EDIT_SOURCES = [("S12", "S9(10)V99"), ("S11", "S9(09)V99")]

VALUES = [
    "0", "1", "-1", "0.01", "-0.01", ".5", "194", "2020", "-2020.55",
    "123.456", "-0.009", "15", "274", "747", "999", "1000", "-274", "-7",
    "1234.5", "99999999.99", "-99999999.99", "9999999999.99",
    "-9999999999.99", "12345678901.23", "-10000000000.00",
    "100000000000", "123456789", "1234567890", "99999999999",
    "4294967296", "-32768", "32767", "65535", "-1234567.891",
    "0.1", "-0.1", "7.07", "1000000", "-1000000",
]


def cobol_source():
    out = []
    a = out.append

    def line(text, area_b=True):
        # Fixed format: indicator column 7 blank, code from column 8 (A) or 12 (B).
        pad = "           " if area_b else "       "
        s = pad + text
        assert len(s) <= 72, s
        a(s)

    line("IDENTIFICATION DIVISION.", False)
    line("PROGRAM-ID. LFGOLDEN.", False)
    line("DATA DIVISION.", False)
    line("WORKING-STORAGE SECTION.", False)
    line("01  WS-SRC              PIC S9(14)V9(4).", False)
    line("01  WS-LIT              PIC X(24).", False)
    line("01  WS-HEX              PIC X(40).", False)
    line("01  WS-HEXDIGITS        PIC X(16) VALUE '0123456789ABCDEF'.", False)
    line("01  WS-I                PIC 9(4) COMP.", False)
    line("01  WS-N                PIC 9(4) COMP.", False)
    line("01  WS-B                PIC 9(4) COMP.", False)
    line("01  WS-HI               PIC 9(4) COMP.", False)
    line("01  WS-LO               PIC 9(4) COMP.", False)
    line("01  WS-BYTES            PIC X(20).", False)
    for src_name, src_pic in EDIT_SOURCES:
        line("01  WS-%s            PIC %s." % (src_name, src_pic), False)
    for name, pic, kind in TARGETS:
        line("01  T-%s." % name, False)
        line("    05  V-%s          PIC %s." % (name, pic), False)
        if kind == "Z":
            line("01  C-%s." % name, False)
            line("    05  W-%s          PIC %s." % (name, pic), False)
    line("PROCEDURE DIVISION.", False)
    line("MAIN-PARA.", False)
    for v in VALUES:
        line("MOVE %s TO WS-SRC" % v)
        line("MOVE '%s' TO WS-LIT" % v)
        line("PERFORM EMIT-ALL")
    line("GOBACK.")
    line("EMIT-ALL.", False)
    for name, pic, kind in TARGETS:
        line("MOVE WS-SRC TO V-%s" % name)
        if kind == "H":
            line("MOVE T-%s TO WS-BYTES" % name)
            line("MOVE LENGTH OF T-%s TO WS-N" % name)
            line("PERFORM TO-HEX")
            line("DISPLAY 'MOVE|%s|' FUNCTION TRIM(WS-LIT) '|'" % name)
            line("    WS-HEX(1:WS-N * 2)")
        else:
            line("DISPLAY 'MOVE|%s|' FUNCTION TRIM(WS-LIT) '|'" % name)
            line("    T-%s" % name)
        if kind == "Z":
            line("COMPUTE W-%s = WS-SRC" % name)
            line("DISPLAY 'COMPUTE|%s|' FUNCTION TRIM(WS-LIT) '|'" % name)
            line("    C-%s" % name)
    for src_name, _ in EDIT_SOURCES:
        line("MOVE WS-SRC TO WS-%s" % src_name)
        for name, pic, kind in TARGETS:
            if kind != "E":
                continue
            line("MOVE WS-%s TO V-%s" % (src_name, name))
            line("DISPLAY 'MOVE-%s|%s|' FUNCTION TRIM(WS-LIT) '|'" % (src_name, name))
            line("    T-%s" % name)
    line("CONTINUE.")
    line("TO-HEX.", False)
    line("MOVE SPACES TO WS-HEX")
    line("PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > WS-N")
    line("    COMPUTE WS-B = FUNCTION ORD(WS-BYTES(WS-I:1)) - 1")
    line("    DIVIDE WS-B BY 16 GIVING WS-HI REMAINDER WS-LO")
    line("    MOVE WS-HEXDIGITS(WS-HI + 1:1)")
    line("      TO WS-HEX(WS-I * 2 - 1:1)")
    line("    MOVE WS-HEXDIGITS(WS-LO + 1:1) TO WS-HEX(WS-I * 2:1)")
    line("END-PERFORM.")
    return "\n".join(out) + "\n"


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "LFGOLDEN.cbl")
        exe = os.path.join(tmp, "lfgolden")
        with open(src, "w") as fh:
            fh.write(cobol_source())
        subprocess.run(["cobc", "-x", "-std=ibm", "-fsign=EBCDIC", "-o", exe, src], check=True)
        env = dict(os.environ, COB_SET_DEBUG="N")
        result = subprocess.run([exe], check=True, stdout=subprocess.PIPE, env=env)
        version = subprocess.run(["cobc", "--version"], check=True, stdout=subprocess.PIPE)
    lines = result.stdout.decode("latin-1").splitlines()
    pics = "\n".join("# %s = %s" % (n, p) for n, p, _ in TARGETS)
    header = (
        "# Generated by src/test/golden/gen_golden.py with %s, flags -std=ibm -fsign=EBCDIC.\n"
        "# Do not edit; rerun the generator. Format: verb|target|source value|bytes\n"
        "# (zoned and edited targets as text between the bars, packed and binary as hex).\n"
        "# MOVE and COMPUTE take the value from S9(14)V9(4); MOVE-S12 and MOVE-S11 first\n"
        "# MOVE it to S9(10)V99 or S9(09)V99 and then MOVE that field to the target.\n"
        "%s\n" % (version.stdout.decode().splitlines()[0], pics)
    )
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", newline="\n") as fh:
        fh.write(header)
        for l in lines:
            fh.write(l.rstrip("\r") + "\n")
    print("wrote %d cases to %s" % (len(lines), out_path))


if __name__ == "__main__":
    main()
