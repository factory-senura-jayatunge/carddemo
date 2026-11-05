      ******************************************************************
      * Emulates z/OS  EXEC PGM=<program>,PARM='<text>'  for programs
      * whose PROCEDURE DIVISION takes the JCL PARM (a halfword length
      * followed by the text), such as CBACT04C.
      *   usage: PARMRUN <program> [<parm text>]
      * The program is loaded as a module through COB_LIBRARY_PATH and
      * its RETURN-CODE becomes the process exit status.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. PARMRUN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-ARGC                 PIC 9(4) BINARY.
       01  WS-PROGRAM              PIC X(8).
       01  WS-PARM-AREA.
           05  WS-PARM-LENGTH      PIC S9(4) BINARY.
           05  WS-PARM-TEXT        PIC X(100).
       PROCEDURE DIVISION.
           ACCEPT WS-ARGC FROM ARGUMENT-NUMBER
           IF WS-ARGC < 1
               DISPLAY 'usage: PARMRUN <program> [<parm>]' UPON SYSERR
               MOVE 16 TO RETURN-CODE
               STOP RUN
           END-IF
           ACCEPT WS-PROGRAM FROM ARGUMENT-VALUE
           MOVE SPACES TO WS-PARM-TEXT
           MOVE 0 TO WS-PARM-LENGTH
           IF WS-ARGC > 1
               ACCEPT WS-PARM-TEXT FROM ARGUMENT-VALUE
               IF WS-PARM-TEXT NOT = SPACES
                   COMPUTE WS-PARM-LENGTH = FUNCTION LENGTH(
                       FUNCTION TRIM(WS-PARM-TEXT TRAILING))
               END-IF
           END-IF
           CALL WS-PROGRAM USING WS-PARM-AREA
               ON EXCEPTION
                   DISPLAY 'PARMRUN: program ' WS-PROGRAM
                       ' not found in COB_LIBRARY_PATH' UPON SYSERR
                   MOVE 16 TO RETURN-CODE
           END-CALL
           STOP RUN.
