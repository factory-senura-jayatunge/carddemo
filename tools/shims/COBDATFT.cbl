      ******************************************************************
      * COBOL port of app/asm/COBDATFT.asm for local GnuCOBOL runs.
      * Interface: CODATECN-REC (app/cpy/CODATECN.cpy).
      *   type 1 in (YYYYMMDD)   -> out type 1 (YYYY-MM-DD)
      *   type 2 in (YYYY-MM-DD) -> out type 2 (YYYYMMDD)
      * Any other combination sets CODATECN-ERROR-MSG to INVALID INPUT,
      * as the assembler does. Like the assembler, only the converted
      * bytes of the output date are changed.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COBDATFT.
       DATA DIVISION.
       LINKAGE SECTION.
       COPY CODATECN.
       PROCEDURE DIVISION USING CODATECN-REC.
           EVALUATE TRUE
               WHEN YYYYMMDD-IN
                   IF CODATECN-INP-DATE(5:1) = '-' OR YYYYMMDD-OP
                       MOVE 'INVALID INPUT' TO CODATECN-ERROR-MSG
                   ELSE
                       MOVE CODATECN-INP-DATE(1:4)
                         TO CODATECN-0UT-DATE(1:4)
                       MOVE '-' TO CODATECN-0UT-DATE(5:1)
                       MOVE CODATECN-INP-DATE(5:2)
                         TO CODATECN-0UT-DATE(6:2)
                       MOVE '-' TO CODATECN-0UT-DATE(8:1)
                       MOVE CODATECN-INP-DATE(7:2)
                         TO CODATECN-0UT-DATE(9:2)
                   END-IF
               WHEN YYYY-MM-DD-IN
                   IF YYYY-MM-DD-OP
                       MOVE 'INVALID INPUT' TO CODATECN-ERROR-MSG
                   ELSE
                       MOVE CODATECN-INP-DATE(1:4)
                         TO CODATECN-0UT-DATE(1:4)
                       MOVE CODATECN-INP-DATE(6:2)
                         TO CODATECN-0UT-DATE(5:2)
                       MOVE CODATECN-INP-DATE(9:2)
                         TO CODATECN-0UT-DATE(7:2)
                   END-IF
               WHEN OTHER
                   MOVE 'INVALID INPUT' TO CODATECN-ERROR-MSG
           END-EVALUATE
           MOVE 0 TO RETURN-CODE
           GOBACK.
