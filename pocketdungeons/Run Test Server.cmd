@echo off
rem Pocket Dungeons test server: double-click to build the mod from source and
rem run it on this machine (world and settings in the run folder).
rem Connect from Minecraft 26.2: Multiplayer, Direct Connection, localhost.
rem Type "stop" in this window to shut down cleanly; closing the window
rem instead skips the final save.
setlocal
cd /d "%~dp0"
title Pocket Dungeons test server
echo Building and starting the server. The first start takes a minute.
echo Connect with Multiplayer, Direct Connection, localhost. Type "stop" here to quit.
call gradlew.bat runServer --console=plain
echo.
echo The server has stopped.
pause
