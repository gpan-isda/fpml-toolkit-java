@echo off
:: compile.bat – thin launcher for compile.ps1
:: Delegates all build logic to compile.ps1 (handles paths with spaces).
:: Requires Java 11+ JDK on PATH.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0compile.ps1" %*
exit /b %ERRORLEVEL%



