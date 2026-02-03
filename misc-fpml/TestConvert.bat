@echo off
REM TestConvert.bat - compile project and run the TestConvertUnit test harness
REM Usage: TestConvert.bat [example-file] [target-version]

:: Change to project root (one level up from misc-fpml)
pushd "%~dp0.."


:: Determine example and target arguments (defaults provided)
set "EXAMPLE=%~1"
set "TARGET=%~2"
if "%EXAMPLE%"=="" set "EXAMPLE=files-fpml/examples/fpml4-7/interest-rate-derivatives/ird-ex01-vanilla-swap.xml"
if "%TARGET%"=="" set "TARGET=5-11"

echo Running TestConvertUnit with example: %EXAMPLE% target: %TARGET%
java -cp "build\classes;lib\xml-apis.jar;lib\xercesimpl.jar" demo.com.handcoded.fpml.TestConvertUnit "%EXAMPLE%" %TARGET%
set "RC=%ERRORLEVEL%"
echo %RC%
if %RC%==0 (
    echo.
    echo TestConvertUnit: PASS exit code... 0
) else (
    echo.
    echo TestConvertUnit: FAIL exit code... %RC%
)

:: keep console open on failure to inspect output
if %RC% NEQ 0 pause
popd
exit /b %RC%

