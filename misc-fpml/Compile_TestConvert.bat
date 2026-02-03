@echo off
REM TestConvert.bat - compile project and run the TestConvertUnit test harness
REM Usage: TestConvert.bat [example-file] [target-version]

:: Change to project root (one level up from misc-fpml)
pushd "%~dp0.."

:: Ensure build output dir exists
if not exist build\classes mkdir build\classes

:compile
echo Compiling project sources...
javac -cp "lib\xml-apis.jar;lib\xercesimpl.jar" -d build\classes @build\all_sources.txt
if errorlevel 1 (
    echo Compilation failed. See javac output above.
    pause
    popd
    exit /b 1
)

:: Determine example and target arguments (defaults provided)
set "EXAMPLE=%~1"
set "TARGET=%~2"
if "%EXAMPLE%"=="" set "EXAMPLE=files-fpml/examples/fpml4-7/interest-rate-derivatives/ird-ex07-ois-swap.xml"
if "%TARGET%"=="" set "TARGET=5-13"

echo Running TestConvertUnit with example: %EXAMPLE% target: %TARGET%
java -cp "build\classes;lib\xml-apis.jar;lib\xercesimpl.jar" demo.com.handcoded.fpml.TestConvertUnit "%EXAMPLE%" %TARGET%
set "RC=%ERRORLEVEL%"
if %RC%==0 (
    echo.
    echo TestConvertUnit: PASS - exit code 0
) else (
    echo.
    echo TestConvertUnit: FAIL - exit code %RC%
)

:: keep console open on failure to inspect output
if %RC% NEQ 0 pause
popd
exit /b %RC%
