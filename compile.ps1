#Requires -Version 3.0
<#
.SYNOPSIS
    Compile the HandCoded FpML Toolkit and produce handcoded.jar.

.DESCRIPTION
    Compiles all Java source trees (src-core, src-coreext, src-dsig,
    src-fpml, src-fpmlext, samples/src-acme) against the JARs bundled
    in lib/ and packages the result into handcoded.jar in the project root.

.PREREQUISITES
    Java 11+ JDK on PATH (java, javac, jar).

.EXAMPLE
    # From the project root:
    pwsh -File compile.ps1
    # or (Windows PowerShell 5):
    powershell -ExecutionPolicy Bypass -File compile.ps1
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
$Root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir  = Join-Path $Root "build\classes"
$LibDir  = Join-Path $Root "lib"
$JarOut  = Join-Path $Root "handcoded.jar"

$Classpath = @(
    "$LibDir\xercesImpl.jar",
    "$LibDir\xml-apis.jar",
    "$LibDir\miglayout15-swing.jar"
) -join [System.IO.Path]::PathSeparator   # ";" on Windows

# ---------------------------------------------------------------------------
# Source directories to compile (relative to project root)
# ---------------------------------------------------------------------------
$SourceDirs = @(
    "src-core",
    "src-coreext",
    "src-dsig",
    "src-fpml",
    "src-fpmlext",
    "samples\src-acme"
)

# ---------------------------------------------------------------------------
# 1. Prepare output directory
# ---------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
# Clean stale class files so incremental state doesn't confuse javac
Get-ChildItem -Recurse -Filter "*.class" $OutDir -ErrorAction SilentlyContinue |
    Remove-Item -Force

# ---------------------------------------------------------------------------
# 2. Collect all .java source files
# ---------------------------------------------------------------------------
Write-Host "Collecting source files..."
$SourceFiles = [System.Collections.Generic.List[string]]::new()
foreach ($Rel in $SourceDirs) {
    $Dir = Join-Path $Root $Rel
    if (Test-Path $Dir) {
        Get-ChildItem -Recurse -Filter "*.java" $Dir |
            ForEach-Object { $SourceFiles.Add($_.FullName) }
    }
}
Write-Host "  Found $($SourceFiles.Count) source files across: $($SourceDirs -join ', ')"

if ($SourceFiles.Count -eq 0) {
    Write-Error "No Java source files found. Check the source directories."
}

# ---------------------------------------------------------------------------
# 3. Write a javac argument file (paths with spaces must be quoted)
# ---------------------------------------------------------------------------
$ArgFile = [System.IO.Path]::GetTempFileName()
try {
    # In a javac @argfile, backslash is the escape character, so Windows paths
    # must use forward slashes (also accepted by javac on Windows) or doubled
    # backslashes.  Forward slashes are the simplest, portable fix.
    $Lines = $SourceFiles | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
    # Write WITHOUT a BOM so javac does not see a spurious leading byte.
    [System.IO.File]::WriteAllLines($ArgFile, $Lines,
        (New-Object System.Text.UTF8Encoding $false))

    # ---------------------------------------------------------------------------
    # 4. Compile
    # ---------------------------------------------------------------------------
    Write-Host ""
    Write-Host "Compiling (--release 11)..."
    & javac `
        -d        "$OutDir"  `
        -cp       "$Classpath" `
        -encoding UTF-8 `
        --release 11 `
        "@$ArgFile"

    if ($LASTEXITCODE -ne 0) {
        Write-Error "javac exited with code $LASTEXITCODE. Fix the errors above and retry."
    }
    Write-Host "  Compilation succeeded."
} finally {
    Remove-Item $ArgFile -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# 5. Create JAR with manifest
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Creating $JarOut ..."

$ManifestFile = [System.IO.Path]::GetTempFileName()
try {
    # Manifest body – note: MUST end with a blank line
    $ManifestContent = @"
Main-Class: demo.com.handcoded.fpml.Validate
Class-Path: lib/xercesImpl.jar lib/xml-apis.jar lib/miglayout15-swing.jar

"@
    [System.IO.File]::WriteAllText($ManifestFile, $ManifestContent, [System.Text.Encoding]::ASCII)

    Push-Location $OutDir
    try {
        & jar cfm "$JarOut" "$ManifestFile" .
        if ($LASTEXITCODE -ne 0) {
            Write-Error "jar exited with code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item $ManifestFile -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
$JarSize = (Get-Item $JarOut).Length
Write-Host ""
Write-Host "============================================================"
Write-Host " Build successful."
Write-Host " Output JAR  : $JarOut  ($("{0:N0}" -f $JarSize) bytes)"
Write-Host " Class files : $OutDir"
Write-Host "============================================================"
Write-Host ""
Write-Host "Usage examples:"
Write-Host "  # Validate an FpML document:"
Write-Host "  java -jar handcoded.jar path\to\fpml-document.xml"
Write-Host ""
Write-Host "  # Convert (demo):"
Write-Host "  java -cp handcoded.jar;lib\xercesImpl.jar;lib\xml-apis.jar demo.com.handcoded.fpml.Convert ..."
Write-Host ""

