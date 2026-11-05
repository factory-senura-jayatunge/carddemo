      ******************************************************************
      * Test driver for the CEEDAYS shim. One request per SYSIN line:
      *   cols  1-3   date string length    cols  4-6   picture length
      *   cols  7-38  date string           cols 39-70  picture string
      * A line starting with END stops the driver. For each request it
      * prints the Lilian day number and the 12-byte feedback token in
      * hex: LILIAN=nnnnnnnnn FC=<24 hex digits>
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEEDAYST.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-LINE.
           05  WS-IN-DATE-LEN      PIC 9(3).
           05  WS-IN-PIC-LEN       PIC 9(3).
           05  WS-IN-DATE          PIC X(32).
           05  WS-IN-PIC           PIC X(32).
           05  FILLER              PIC X(10).
       01  WS-DATE.
           05  WS-DATE-LEN         PIC S9(4) BINARY.
           05  WS-DATE-TEXT        PIC X(256).
       01  WS-PICTURE.
           05  WS-PIC-LEN          PIC S9(4) BINARY.
           05  WS-PIC-TEXT         PIC X(256).
       01  WS-LILIAN               PIC S9(9) BINARY.
       01  WS-LILIAN-OUT           PIC 9(9).
       01  WS-FEEDBACK             PIC X(12).
       PROCEDURE DIVISION.
           PERFORM UNTIL WS-LINE(1:3) = 'END'
               MOVE SPACES TO WS-LINE
               ACCEPT WS-LINE
               IF WS-LINE(1:3) NOT = 'END'
                   MOVE WS-IN-DATE-LEN TO WS-DATE-LEN
                   MOVE WS-IN-DATE TO WS-DATE-TEXT
                   MOVE WS-IN-PIC-LEN TO WS-PIC-LEN
                   MOVE WS-IN-PIC TO WS-PIC-TEXT
                   MOVE -1 TO WS-LILIAN
                   MOVE ALL X'FF' TO WS-FEEDBACK
                   CALL 'CEEDAYS' USING WS-DATE WS-PICTURE WS-LILIAN
                                        WS-FEEDBACK
                   MOVE WS-LILIAN TO WS-LILIAN-OUT
                   DISPLAY 'LILIAN=' WS-LILIAN-OUT
                           ' FC=' FUNCTION HEX-OF(WS-FEEDBACK)
               END-IF
           END-PERFORM
           STOP RUN.
