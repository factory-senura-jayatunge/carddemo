      ******************************************************************
      * Local stand-in for the z/OS Language Environment service
      * CEEDAYS (convert a date to a Lilian day number, 15 Oct 1582 =
      * day 1). Supports picture strings made of YYYY, MM and DD plus
      * literal separator characters, which covers CardDemo's use
      * ('YYYY-MM-DD', 'YYYYMMDD'). The 12-byte feedback token uses the
      * LE condition layout: severity, message number, flags X'59' and
      * facility 'CEE' in EBCDIC, which is what CSUTLDTC compares.
      *   CEE2507 insufficient data     CEE2508 date value not valid
      *   CEE2513 outside supported range CEE2517 month not valid
      *   CEE2518 bad picture string    CEE2520 non-numeric data
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEEDAYS.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-PIC                  PIC X(256).
       01  WS-DATE                 PIC X(256).
       01  WS-PIC-LEN              PIC S9(4) BINARY.
       01  WS-DATE-LEN             PIC S9(4) BINARY.
       01  WS-I                    PIC S9(4) BINARY.
       01  WS-Y-POS                PIC S9(4) BINARY VALUE 0.
       01  WS-M-POS                PIC S9(4) BINARY VALUE 0.
       01  WS-D-POS                PIC S9(4) BINARY VALUE 0.
       01  WS-YYYY                 PIC 9(4).
       01  WS-MM                   PIC 9(2).
       01  WS-DD                   PIC 9(2).
       01  WS-YYYYMMDD             PIC 9(8).
       01  WS-MSG                  PIC S9(4) BINARY VALUE 0.
       01  WS-MAX-DAY              PIC 9(2).
       01  WS-TOKEN.
           05  WS-TOKEN-SEV        PIC S9(4) BINARY.
           05  WS-TOKEN-MSG        PIC S9(4) BINARY.
           05  WS-TOKEN-FLAGS      PIC X.
           05  WS-TOKEN-FACILITY   PIC X(3).
           05  WS-TOKEN-ISI        PIC S9(9) BINARY.
       LINKAGE SECTION.
       01  LS-DATE.
           05  LS-DATE-LEN         PIC S9(4) BINARY.
           05  LS-DATE-TEXT        PIC X(256).
       01  LS-PICTURE.
           05  LS-PIC-LEN          PIC S9(4) BINARY.
           05  LS-PIC-TEXT         PIC X(256).
       01  LS-LILIAN               PIC S9(9) BINARY.
       01  LS-FEEDBACK             PIC X(12).
       PROCEDURE DIVISION USING LS-DATE LS-PICTURE LS-LILIAN
                                LS-FEEDBACK.
           MOVE 0 TO LS-LILIAN WS-MSG WS-Y-POS WS-M-POS WS-D-POS
           MOVE LS-PIC-LEN  TO WS-PIC-LEN
           MOVE LS-DATE-LEN TO WS-DATE-LEN
           MOVE SPACES TO WS-PIC WS-DATE
           IF WS-PIC-LEN < 1 OR WS-PIC-LEN > 256
               MOVE 2518 TO WS-MSG
           ELSE
               MOVE LS-PIC-TEXT(1:WS-PIC-LEN) TO WS-PIC
               MOVE FUNCTION UPPER-CASE(WS-PIC) TO WS-PIC
               IF WS-DATE-LEN > 0 AND WS-DATE-LEN <= 256
                   MOVE LS-DATE-TEXT(1:WS-DATE-LEN) TO WS-DATE
               END-IF
               PERFORM PARSE-PICTURE
           END-IF
           IF WS-MSG = 0
               PERFORM EXTRACT-FIELDS
           END-IF
           IF WS-MSG = 0
               PERFORM VALIDATE-DATE
           END-IF
           IF WS-MSG = 0
               MOVE LOW-VALUES TO LS-FEEDBACK
               COMPUTE WS-YYYYMMDD = WS-YYYY * 10000 + WS-MM * 100
                                   + WS-DD
      *        INTEGER-OF-DATE counts from 1601-01-01 = 1, which is
      *        Lilian day 6654. It rejects earlier years, so dates in
      *        1582-1600 are shifted 400 years (146097 days) forward.
               IF WS-YYYY < 1601
                   COMPUTE LS-LILIAN =
                       FUNCTION INTEGER-OF-DATE(WS-YYYYMMDD + 4000000)
                       + 6653 - 146097
               ELSE
                   COMPUTE LS-LILIAN =
                       FUNCTION INTEGER-OF-DATE(WS-YYYYMMDD) + 6653
               END-IF
           ELSE
               MOVE 3 TO WS-TOKEN-SEV
               MOVE WS-MSG TO WS-TOKEN-MSG
               MOVE X'59' TO WS-TOKEN-FLAGS
               MOVE X'C3C5C5' TO WS-TOKEN-FACILITY
               MOVE 0 TO WS-TOKEN-ISI
               MOVE WS-TOKEN TO LS-FEEDBACK
           END-IF
           GOBACK.

       PARSE-PICTURE.
           PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > WS-PIC-LEN
               EVALUATE TRUE
                   WHEN WS-PIC(WS-I:4) = 'YYYY' AND WS-Y-POS = 0
                       MOVE WS-I TO WS-Y-POS
                       ADD 3 TO WS-I
                   WHEN WS-PIC(WS-I:2) = 'MM' AND WS-M-POS = 0
                       MOVE WS-I TO WS-M-POS
                       ADD 1 TO WS-I
                   WHEN WS-PIC(WS-I:2) = 'DD' AND WS-D-POS = 0
                       MOVE WS-I TO WS-D-POS
                       ADD 1 TO WS-I
                   WHEN WS-PIC(WS-I:1) IS ALPHABETIC
                        AND WS-PIC(WS-I:1) NOT = SPACE
                       MOVE 2518 TO WS-MSG
                   WHEN OTHER
                       CONTINUE
               END-EVALUATE
           END-PERFORM
           IF WS-Y-POS = 0 OR WS-M-POS = 0 OR WS-D-POS = 0
               MOVE 2518 TO WS-MSG
           END-IF.

       EXTRACT-FIELDS.
           IF WS-DATE-LEN < WS-PIC-LEN
              OR WS-DATE(1:WS-PIC-LEN) = SPACES
               MOVE 2507 TO WS-MSG
           ELSE
               IF WS-DATE(WS-Y-POS:4) IS NOT NUMERIC
                  OR WS-DATE(WS-M-POS:2) IS NOT NUMERIC
                  OR WS-DATE(WS-D-POS:2) IS NOT NUMERIC
                   MOVE 2520 TO WS-MSG
               ELSE
                   MOVE WS-DATE(WS-Y-POS:4) TO WS-YYYY
                   MOVE WS-DATE(WS-M-POS:2) TO WS-MM
                   MOVE WS-DATE(WS-D-POS:2) TO WS-DD
               END-IF
           END-IF.

       VALIDATE-DATE.
           EVALUATE TRUE
               WHEN WS-MM < 1 OR WS-MM > 12
                   MOVE 2517 TO WS-MSG
               WHEN WS-YYYY < 1582 OR
                    (WS-YYYY = 1582 AND
                     (WS-MM < 10 OR (WS-MM = 10 AND WS-DD < 15)))
                   MOVE 2513 TO WS-MSG
               WHEN OTHER
                   EVALUATE WS-MM
                       WHEN 2
                           IF FUNCTION MOD(WS-YYYY, 400) = 0 OR
                              (FUNCTION MOD(WS-YYYY, 4) = 0 AND
                               FUNCTION MOD(WS-YYYY, 100) NOT = 0)
                               MOVE 29 TO WS-MAX-DAY
                           ELSE
                               MOVE 28 TO WS-MAX-DAY
                           END-IF
                       WHEN 4 WHEN 6 WHEN 9 WHEN 11
                           MOVE 30 TO WS-MAX-DAY
                       WHEN OTHER
                           MOVE 31 TO WS-MAX-DAY
                   END-EVALUATE
                   IF WS-DD < 1 OR WS-DD > WS-MAX-DAY
                       MOVE 2508 TO WS-MSG
                   END-IF
           END-EVALUATE.
