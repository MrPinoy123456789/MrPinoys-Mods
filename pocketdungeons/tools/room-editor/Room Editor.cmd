@echo off
rem Pocket Dungeons Room Editor: double-click to run.
rem Finds your own 26.2 client jar, opens the mod's room folder for editing,
rem and starts the editor in your browser. Close this window to stop it.
setlocal
cd /d "%~dp0"
title Pocket Dungeons Room Editor

where npm >nul 2>nul
if errorlevel 1 (
  echo Node.js is not installed. Install Node 22 from https://nodejs.org and run this again.
  pause
  exit /b 1
)

if not exist node_modules (
  echo First run: installing the editor's packages. This takes a minute.
  call npm install
  if errorlevel 1 (
    echo npm install failed; see the messages above.
    pause
    exit /b 1
  )
)

set "MC_JAR=%APPDATA%\.minecraft\versions\26.2\26.2.jar"
if not exist "%MC_JAR%" set "MC_JAR=%USERPROFILE%\.gradle\caches\fabric-loom\26.2\minecraft-client.jar"
if not exist "%MC_JAR%" (
  echo Could not find a Minecraft 26.2 client jar.
  echo Launch Minecraft 26.2 once from the official launcher, then run this again.
  pause
  exit /b 1
)

set "PACK_DIR=%~dp0..\..\src\main\resources\data\pocketdungeons"
set "PACK_WRITABLE=1"

echo Jar:   %MC_JAR%
echo Rooms: %PACK_DIR%
echo Opening the editor in your browser. Close this window to stop it.
call npx vite --open "/?dev=1"
