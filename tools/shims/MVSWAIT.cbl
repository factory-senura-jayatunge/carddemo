      ******************************************************************
      * COBOL port of app/asm/MVSWAIT.asm for local GnuCOBOL runs.
      * The assembler issues STIMER WAIT,BINTVL= with the fullword the
      * caller passes, a delay in hundredths of a second.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MVSWAIT.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-NANOSECONDS          PIC 9(18) BINARY.
       LINKAGE SECTION.
       01  LS-CENTISECONDS         PIC 9(8) BINARY.
       PROCEDURE DIVISION USING LS-CENTISECONDS.
           COMPUTE WS-NANOSECONDS = LS-CENTISECONDS * 10000000
           CALL 'CBL_GC_NANOSLEEP' USING WS-NANOSECONDS
           MOVE 0 TO RETURN-CODE
           GOBACK.
