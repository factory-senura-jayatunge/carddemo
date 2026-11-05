//FTPJCLS  JOB 'FTP JCL',CLASS=A,MSGCLASS=H,MSGLEVEL=(1,1),             JOB01598
//             REGION=0M,NOTIFY=&SYSUID,TIME=1440
//*
//*
//******************************************************************
//* Copyright Amazon.com, Inc. or its affiliates.
//* All Rights Reserved.
//*
//* Licensed under the Apache License, Version 2.0 (the "License").
//* You may not use this file except in compliance with the License.
//* You may obtain a copy of the License at
//*
//*    http://www.apache.org/licenses/LICENSE-2.0
//*
//* Unless required by applicable law or agreed to in writing,
//* software distributed under the License is distributed on an
//* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
//* either express or implied. See the License for the specific
//* language governing permissions and limitations under the License
//******************************************************************
//*********************************************************************
//* FTP JOB TO RECEIVE A FILE
//* MY.FTP.SERVER.COM IS THE FTP SERVER NAME(SUBJECT TO CHANGE FOR SITE)
//* USER ID AND PASSWORD ARE NOT IN THIS JCL. FTP READS THEM FROM THE
//* NETRC DATA SET OF THE SUBMITTING USER (SCHEDULER ID OR TSO ID).
//* FOLDER TO FTP THE MAINFRAME FILE
//* PUT COMMAND FOR SENDING MAINFRAME FILE TO TXT
//* CHANGE THE PUT COMMAND TO GET TO RECEIVE FILE INTO MAINFRAME
//*
//* ONE-TIME NETRC SETUP, ONCE PER SUBMITTING USER ID:
//* 1. ALLOCATE userid.NETRC AS A PS DATA SET (OR A PDS MEMBER AND
//*    CHANGE THE NETRC DD), RECFM=FB, LRECL=80, ONE TRACK.
//* 2. ADD ONE LINE FOR THE SERVER. KEYWORDS MUST BE LOWER CASE AND
//*    COMMENTS ARE NOT ALLOWED IN THE DATA SET:
//*      machine MY.FTP.SERVER.COM login userid password pw
//*    THE MACHINE NAME MUST MATCH THE HOST ON THE FIRST SYSIN LINE.
//* 3. PROTECT THE DATA SET SO ONLY ITS OWNER CAN READ IT, E.G.
//*      ADDSD 'userid.NETRC' UACC(NONE)
//*
//* TO PASS THE HOST AS A PARM INSTEAD, UNCOMMENT THE PARM LINE, ADD
//* A COMMA AFTER REGION=2048K AND DELETE THE HOST LINE FROM SYSIN.
//*********************************************************************
//STEP1 EXEC PGM=FTP,REGION=2048K
//*          PARM='MY.FTP.SERVER.COM (EXIT TIMEOUT 20'
//NETRC DD DISP=SHR,DSN=&SYSUID..NETRC
//SYSIN DD *
 MY.FTP.SERVER.COM
 ASCII
 pwd
 dir
 cd /ftpfolder
 PUT 'AWS.M2.CARDEMO.FTP.TEST' welcome.txt
 QUIT
//*
