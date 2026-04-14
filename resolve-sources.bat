@echo off
:: resolve-sources.bat
:: ---------------------------------------------------------------------------
:: Sets up directory junctions so that this toolkit workspace uses the
:: canonical schemas and test-cases from an external FpML validation
:: repository as its single source of truth.
::
:: Usage:
::   resolve-sources.bat
::
:: Before running, edit files-fpml\external-source.properties and set
::   FPML_VALIDATION_REPO=<path-to-your-fpml-validation-shared-clone>
::
:: The script reads that property file and creates NTFS directory junctions
:: (equivalent to symlinks) under files-fpml\schemas\ and
:: files-fpml\test-cases\ pointing at the external repository.
::
:: Administrator rights are NOT required on Windows 10 1703+ for NTFS
:: junctions created by mklink /J.
:: ---------------------------------------------------------------------------

setlocal EnableDelayedExpansion

:: ---- Determine script directory -----------------------------------------
set "TOOLKIT_ROOT=%~dp0"
if "%TOOLKIT_ROOT:~-1%"=="\" set "TOOLKIT_ROOT=%TOOLKIT_ROOT:~0,-1%"

set "PROPS=%TOOLKIT_ROOT%\files-fpml\external-source.properties"
set "FILES_FPML=%TOOLKIT_ROOT%\files-fpml"

:: ---- Read property file --------------------------------------------------
if not exist "%PROPS%" (
    echo ERROR: Property file not found: %PROPS%
    echo Please create it (copy from files-fpml\external-source.properties.example^).
    exit /b 1
)

set "FPML_VALIDATION_REPO="
set "FPML_SCHEMAS_REPO="

for /f "usebackq tokens=1,* delims==" %%A in ("%PROPS%") do (
    set "_key=%%A"
    set "_val=%%B"
    :: Trim leading whitespace from value
    for /f "tokens=* delims= " %%V in ("!_val!") do set "_val=%%V"
    if "!_key!"=="FPML_VALIDATION_REPO" set "FPML_VALIDATION_REPO=!_val!"
    if "!_key!"=="FPML_SCHEMAS_REPO"    set "FPML_SCHEMAS_REPO=!_val!"
)

:: ---- Validate ------------------------------------------------------------
if "!FPML_VALIDATION_REPO!"=="" (
    echo ERROR: FPML_VALIDATION_REPO is not set in %PROPS%
    echo Edit the file and set the path to your fpml-validation-shared clone.
    exit /b 1
)

if not exist "!FPML_VALIDATION_REPO!" (
    echo ERROR: FPML_VALIDATION_REPO directory does not exist:
    echo   !FPML_VALIDATION_REPO!
    exit /b 1
)

:: ---- Determine schema source ---------------------------------------------
set "SCHEMA_SRC=!FPML_SCHEMAS_REPO!"
if "!SCHEMA_SRC!"=="" set "SCHEMA_SRC=!FPML_VALIDATION_REPO!"

:: ---- Create junctions ----------------------------------------------------

call :MakeJunction "%FILES_FPML%\schemas"    "!SCHEMA_SRC!\schemas"
call :MakeJunction "%FILES_FPML%\test-cases" "!FPML_VALIDATION_REPO!\test-cases"
call :MakeJunction "%FILES_FPML%\data"       "!FPML_VALIDATION_REPO!\data"

echo.
echo Done.  Junction targets:
echo   schemas    -^> !SCHEMA_SRC!\schemas
echo   test-cases -^> !FPML_VALIDATION_REPO!\test-cases
echo   data       -^> !FPML_VALIDATION_REPO!\data
exit /b 0

:: --------------------------------------------------------------------------
:: Subroutine: MakeJunction <link> <target>
:: Creates (or updates) an NTFS directory junction.
:: --------------------------------------------------------------------------
:MakeJunction
set "_link=%~1"
set "_tgt=%~2"

if not exist "!_tgt!" (
    echo WARNING: Junction target does not exist, skipping: !_tgt!
    goto :eof
)

if exist "!_link!" (
    :: Already a junction — remove it so mklink /J can recreate it
    :: (rd /s /q on a junction only removes the link, not the target)
    rd "!_link!" 2>nul
    if exist "!_link!" (
        echo WARNING: Could not remove existing path !_link! — skipping.
        goto :eof
    )
)

mklink /J "!_link!" "!_tgt!" >nul
if errorlevel 1 (
    echo ERROR: mklink failed for !_link! -^> !_tgt!
) else (
    echo Created junction: !_link!
)
goto :eof

