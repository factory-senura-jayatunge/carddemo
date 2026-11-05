      ******************************************************************
      * Test driver for CBSTM03B (app/cbl/CBSTM03B.CBL), filling the
      * LK-M03B-AREA the way CBSTM03A does. One request per SYSIN line:
      *   cols  1-8   DD name   col 9 operation (O C R K W Z)
      *   cols 10-13  key length              cols 14-38 key
      * A line starting with END stops the driver. The return code is
      * preset to '..' so a request CBSTM03B ignores is visible. Each
      * request prints  RC=xx DATA=<record, trailing spaces trimmed>
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBSTM03B-TEST.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-LINE.
           05  WS-IN-DD            PIC X(08).
           05  WS-IN-OPER          PIC X(01).
           05  WS-IN-KEY-LN        PIC 9(04).
           05  WS-IN-KEY           PIC X(25).
           05  FILLER              PIC X(42).
       01  WS-M03B-AREA.
           05  WS-M03B-DD          PIC X(08).
           05  WS-M03B-OPER        PIC X(01).
           05  WS-M03B-RC          PIC X(02).
           05  WS-M03B-KEY         PIC X(25).
           05  WS-M03B-KEY-LN      PIC S9(4).
           05  WS-M03B-FLDT        PIC X(1000).
       PROCEDURE DIVISION.
           PERFORM UNTIL WS-LINE(1:3) = 'END'
               MOVE SPACES TO WS-LINE
               ACCEPT WS-LINE
               IF WS-LINE(1:3) NOT = 'END'
                   MOVE WS-IN-DD TO WS-M03B-DD
                   MOVE WS-IN-OPER TO WS-M03B-OPER
                   MOVE '..' TO WS-M03B-RC
                   MOVE WS-IN-KEY TO WS-M03B-KEY
                   MOVE WS-IN-KEY-LN TO WS-M03B-KEY-LN
                   MOVE SPACES TO WS-M03B-FLDT
                   CALL 'CBSTM03B' USING WS-M03B-AREA
                   DISPLAY 'RC=' WS-M03B-RC ' DATA='
                       FUNCTION TRIM(WS-M03B-FLDT TRAILING)
               END-IF
           END-PERFORM
           STOP RUN.
