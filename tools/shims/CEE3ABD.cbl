      ******************************************************************
      * Local stand-in for the z/OS Language Environment service
      * CEE3ABD (terminate the enclave with a user abend code).
      * CardDemo calls it as CALL 'CEE3ABD' USING ABCODE, TIMING and,
      * in a few programs, with no arguments. There is no abend under
      * GnuCOBOL, so this reports the code on stderr and ends the run
      * unit with return code 16, which the runner treats as an abend.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEE3ABD.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-CODE                 PIC 9(4) VALUE 0.
       LINKAGE SECTION.
       01  LS-ABCODE               PIC S9(9) BINARY.
       01  LS-TIMING               PIC S9(9) BINARY.
       PROCEDURE DIVISION USING LS-ABCODE LS-TIMING.
           IF ADDRESS OF LS-ABCODE NOT = NULL
               MOVE LS-ABCODE TO WS-CODE
           END-IF
           DISPLAY 'CEE3ABD: USER ABEND U' WS-CODE UPON SYSERR
           MOVE 16 TO RETURN-CODE
           STOP RUN.
