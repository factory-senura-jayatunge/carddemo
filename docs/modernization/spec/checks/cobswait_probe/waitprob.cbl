      * Probe for COBSWAIT: same data items and statements as
      * app/cbl/COBSWAIT.cbl:30-37, but DISPLAYs the binary value that
      * would be passed to MVSWAIT instead of calling it.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. WAITPROB.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 MVSWAIT-TIME                    PIC 9(8) COMP.
       01 PARM-VALUE                      PIC X(8).
       PROCEDURE DIVISION.
           ACCEPT PARM-VALUE      FROM SYSIN.
           MOVE  PARM-VALUE       TO MVSWAIT-TIME.
           DISPLAY 'PARM=[' PARM-VALUE '] TIME=' MVSWAIT-TIME.
           STOP RUN.
