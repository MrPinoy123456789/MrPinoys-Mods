@echo off
rem Pocket Dungeons test server control. Usage: server start ^| stop ^| status ^| say ^| cmd ^| chat
rem See README.md in this folder.
node "%~dp0pdserver.mjs" %*
