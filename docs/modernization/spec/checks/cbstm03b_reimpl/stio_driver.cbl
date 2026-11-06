      ******************************************************************
      * Equivalence-check driver for CBSTM03B (app/cbl/CBSTM03B.CBL).
      * One request per SYSIN line:
      *   cols  1-8   DD name   col 9 operation
      *   cols 10-13  key length              cols 14-38 key
      * A line starting with END stops the driver. The request area is
      * kept between calls, as in CBSTM03A: only DD, operation, key and
      * key length are set, so the return code and the 1000-byte data
      * area show what CBSTM03B left in them. They start as spaces.
      * Each call prints  RC=xx|<all 1000 bytes of the data area>
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. STIODRV.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-LINE.
           05  WS-IN-DD            PIC X(08).
           05  WS-IN-OPER          PIC X(01).
           05  WS-IN-KEY-LN        PIC 9(04).
           05  WS-IN-KEY           PIC X(25).
           05  FILLER              PIC X(42).
       01  WS-M03B-AREA.
           05  WS-M03B-DD          PIC X(08) VALUE SPACES.
           05  WS-M03B-OPER        PIC X(01) VALUE SPACES.
           05  WS-M03B-RC          PIC X(02) VALUE SPACES.
           05  WS-M03B-KEY         PIC X(25) VALUE SPACES.
           05  WS-M03B-KEY-LN      PIC S9(4) VALUE ZERO.
           05  WS-M03B-FLDT        PIC X(1000) VALUE SPACES.
       PROCEDURE DIVISION.
           PERFORM UNTIL WS-LINE(1:3) = 'END'
               MOVE SPACES TO WS-LINE
               ACCEPT WS-LINE
               IF WS-LINE(1:3) NOT = 'END'
                   MOVE WS-IN-DD TO WS-M03B-DD
                   MOVE WS-IN-OPER TO WS-M03B-OPER
                   MOVE WS-IN-KEY TO WS-M03B-KEY
                   MOVE WS-IN-KEY-LN TO WS-M03B-KEY-LN
                   CALL 'CBSTM03B' USING WS-M03B-AREA
                   DISPLAY 'RC=' WS-M03B-RC '|' WS-M03B-FLDT
               END-IF
           END-PERFORM
           STOP RUN.
