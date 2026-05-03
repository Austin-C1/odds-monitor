@echo off
setlocal

for %%I in ("%~dp0.") do set "ROOT_DIR=%%~fI"
set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "SCRIPT_PATH=%ROOT_DIR%\launch-odds-monitor.ps1"

if not exist "%POWERSHELL_EXE%" (
  echo PowerShell not found: "%POWERSHELL_EXE%"
  exit /b 1
)

if not exist "%SCRIPT_PATH%" (
  echo Launch script not found: "%SCRIPT_PATH%"
  exit /b 1
)

"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_PATH%"
exit /b %ERRORLEVEL%


