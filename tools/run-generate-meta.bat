@echo on

pushd "%~dp0.."

setlocal
set DATADIR=files-fpml\data
set FpMLVer=5-13
rem Force compilation of Java sources in tools (don't skip even if .class files exist)
rem if exist tools\Metagenerator.class goto skip_javac

echo Compiling Java sources in tools (always compile to pick up changes)...
if exist tools\javac_files.lst del /f /q tools\javac_files.lst 2>nul
rem create file list
dir /b /s "tools\*.java" > tools\javac_files.lst 2>nul
if not exist tools\javac_files.lst (
  echo No Java sources found under tools\
  goto skip_javac
)

rem Compile Java sources in tools using wildcard (single javac invocation)
if exist javac_metagen.err del /f /q javac_metagen.err 2>nul
if exist tools\javac_failed.flag del /f /q tools\javac_failed.flag 2>nul
javac -d tools -cp tools tools\*.java 2>javac_metagen.err
if ERRORLEVEL 1 (
  echo 1>tools\javac_failed.flag
  echo Note: javac returned a non-zero exit code; see javac_metagen.err for details; continuing (existing .class files may still be present).
)

:skip_javac

rem Define targets here; modify as needed
set "TARGETS=confirmation legal pretrade reporting recordkeeping transparency"

for %%T in (%TARGETS%) do (
  call :gen %%T
)

echo All targets processed.
endlocal
exit /b 0

:compile
rem %1 = quoted source path
set "src=%~1"
echo Compiling "%src%"
javac -d tools -cp tools "%src%" 2>>javac_metagen.err
if ERRORLEVEL 1 (
  echo 1>tools\javac_failed.flag
)

goto :eof

:gen
rem %1 is the target name
set "TARGET=%~1"
echo Generating %TARGET%...
call java -cp "tools" Metagenerator --schema-dir "files-fpml\schemas\fpml%FpMLVer%\%TARGET%" --generate-meta "files-fpml\meta\fpml-%FpMLVer%-%TARGET%.xml" --data-dir "%DATADIR%"
if %ERRORLEVEL% NEQ 0 echo Metagenerator failed for %TARGET%
call java -cp "tools" RunEnsureDefaults "files-fpml\meta\fpml-%FpMLVer%-%TARGET%.xml"
exit /b 0
