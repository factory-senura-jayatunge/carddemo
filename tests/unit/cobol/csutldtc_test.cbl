      ******************************************************************
      * Test driver for CSUTLDTC (app/cbl/CSUTLDTC.cbl). One request per
      * SYSIN line: cols 1-10 date, cols 11-20 date format. A line
      * starting with END stops the driver. For each request it prints
      *   RC=nnnn MSG=<the 80-byte LS-RESULT area>
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CSUTLDTT.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-LINE.
           05  WS-IN-DATE          PIC X(10).
           05  WS-IN-FORMAT        PIC X(10).
           05  FILLER              PIC X(60).
       01  WS-RESULT               PIC X(80).
       01  WS-RC                   PIC 9(4).
       PROCEDURE DIVISION.
           PERFORM UNTIL WS-LINE(1:3) = 'END'
               MOVE SPACES TO WS-LINE
               ACCEPT WS-LINE
               IF WS-LINE(1:3) NOT = 'END'
                   MOVE ALL '*' TO WS-RESULT
                   CALL 'CSUTLDTC' USING WS-IN-DATE WS-IN-FORMAT
                                         WS-RESULT
                   MOVE RETURN-CODE TO WS-RC
                   DISPLAY 'RC=' WS-RC ' MSG=' WS-RESULT
               END-IF
           END-PERFORM
           MOVE 0 TO RETURN-CODE
           STOP RUN.
