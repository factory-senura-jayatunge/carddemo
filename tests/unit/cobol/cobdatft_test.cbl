      ******************************************************************
      * Test driver for the COBDATFT shim. Each SYSIN line is an 80-byte
      * CODATECN-REC (app/cpy/CODATECN.cpy) passed as is; the driver
      * prints the record after the call as OUT=<80 bytes>. A line
      * starting with END stops the driver.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COBDATFT-TEST.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-LINE                 PIC X(80).
       COPY CODATECN.
       PROCEDURE DIVISION.
           PERFORM UNTIL WS-LINE(1:3) = 'END'
               MOVE SPACES TO WS-LINE
               ACCEPT WS-LINE
               IF WS-LINE(1:3) NOT = 'END'
                   MOVE WS-LINE TO CODATECN-REC
                   CALL 'COBDATFT' USING CODATECN-REC
                   DISPLAY 'OUT=' CODATECN-REC
               END-IF
           END-PERFORM
           STOP RUN.
