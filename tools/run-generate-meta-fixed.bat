@echo off
pushd "%~dp0.."
setlocal
set LOG=tools\run-generate-meta-fixed.log
echo === run-generate-meta-fixed: %DATE% %TIME% > "%LOG%"
set DATADIR=files-fpml\data
set FpMLVer=5-12

echo Compiling Java sources in tools (always compile to pick up changes)... >> "%LOG%"
if exist javac_metagen.err del /f /q javac_metagen.err 2>nul
if exist tools\javac_failed.flag del /f /q tools\javac_failed.flag 2>nul
javac -d tools -cp tools "tools\*.java" 2>javac_metagen.err
if %ERRORLEVEL% NEQ 0 (
  echo javac failed with exit %ERRORLEVEL% - see javac_metagen.err >> "%LOG%"
) else (
  echo javac finished OK >> "%LOG%"
)

rem Define targets here; modify as needed
set TARGETS=confirmation legal pretrade reporting recordkeeping transparency
for %%T in (%TARGETS%) do call :process %%T

echo All targets processed >> "%LOG%"
endlocal
popd
exit /b 0

:process
rem %1 is the target name
set TARGET=%~1
echo -------------------------------------------------- >> "%LOG%"
echo Generating %TARGET%... >> "%LOG%"
java -cp "tools" Metagenerator --schema-dir "files-fpml\schemas\fpml%FpMLVer%\%TARGET%" --generate-meta "files-fpml\meta\fpml-%FpMLVer%-%TARGET%.xml" --data-dir "%DATADIR%" >> "%LOG%" 2>&1
if %ERRORLEVEL% NEQ 0 echo Metagenerator failed for %TARGET% with exit %ERRORLEVEL% >> "%LOG%"

java -cp "tools" RunEnsureDefaults "files-fpml\meta\fpml-%FpMLVer%-%TARGET%.xml" >> "%LOG%" 2>&1
if %ERRORLEVEL% NEQ 0 echo RunEnsureDefaults failed for %TARGET% with exit %ERRORLEVEL% >> "%LOG%"

goto :eof
