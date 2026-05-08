<#
.SYNOPSIS
  Generates candidate-delta.xml files for every consecutive FpML version pair
  by running XsdSchemaDiffer against the local schema directories.

.DESCRIPTION
  Covers:
    - All consecutive FpML 4.x pairs (flat schema dirs, view = "all")
    - The 4-10 → 5-0 bridge (flat → confirmation/reporting)
    - All consecutive FpML 5.x pairs for every view common to both versions

  Output files land in files-fpml/conversion-profiles/ and are ready for
  DeltaProfileReviewer.
#>
param(
    [string]$ClassPath = "build\classes;lib\xercesImpl.jar;lib\xml-apis.jar"
)

$ErrorActionPreference = "Continue"
$root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$schemas = Join-Path $root "files-fpml\schemas"
$out     = Join-Path $root "files-fpml\conversion-profiles"
$main    = "com.handcoded.meta.tools.XsdSchemaDiffer"

# Ensure output directory exists
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }

$ok    = 0
$fail  = 0
$total = 0

function Run-Differ {
    param($fromDir, $toDir, $fromVer, $toVer, $view)

    $outFile = Join-Path $out "candidate-$fromVer-to-$toVer-$view.xml"
    $label   = "$fromVer → $toVer  [$view]"
    Write-Host "  Diffing $label ..." -NoNewline

    $proc = Start-Process -FilePath "java" `
        -ArgumentList @("-cp", $ClassPath, $main,
                        "`"$fromDir`"", "`"$toDir`"",
                        $fromVer, $toVer, $view, "`"$outFile`"") `
        -Wait -PassThru -NoNewWindow `
        -RedirectStandardOutput "$env:TEMP\differ-stdout.tmp" `
        -RedirectStandardError  "$env:TEMP\differ-stderr.tmp"

    $stdout = Get-Content "$env:TEMP\differ-stdout.tmp" -Raw -ErrorAction SilentlyContinue
    $stderr = Get-Content "$env:TEMP\differ-stderr.tmp" -Raw -ErrorAction SilentlyContinue

    if ($proc.ExitCode -eq 0) {
        # Extract candidate count from stdout
        $m = [regex]::Match($stdout, '=> (\d+) candidate')
        $cnt = if ($m.Success) { $m.Groups[1].Value } else { "?" }
        Write-Host " OK  ($cnt candidates)" -ForegroundColor Green
        $script:ok++
    } else {
        Write-Host " FAILED" -ForegroundColor Red
        if ($stderr) { Write-Host "    $($stderr.Trim())" -ForegroundColor Yellow }
        $script:fail++
    }
    $script:total++
}

# ─────────────────────────────────────────────────────────────
# 1. FpML 4.x consecutive pairs (flat schema dirs)
# ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== FpML 4.x consecutive pairs (flat) ===" -ForegroundColor Cyan

$v4 = @("4-0","4-1","4-2","4-3","4-4","4-5","4-6","4-7","4-8","4-9","4-10")
for ($i = 0; $i -lt $v4.Count - 1; $i++) {
    $fv = $v4[$i]; $tv = $v4[$i+1]
    $fd = Join-Path $schemas "fpml$fv"
    $td = Join-Path $schemas "fpml$tv"
    if ((Test-Path $fd) -and (Test-Path $td)) {
        Run-Differ $fd $td $fv $tv "all"
    } else {
        Write-Host "  Skipping $fv → $tv (directory missing)" -ForegroundColor DarkYellow
    }
}

# ─────────────────────────────────────────────────────────────
# 2. FpML 4-10 → 5-0 bridge (flat → confirmation / reporting)
# ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== FpML 4-10 → 5-0 bridge ===" -ForegroundColor Cyan

$fd410 = Join-Path $schemas "fpml4-10"
foreach ($view in @("confirmation","reporting")) {
    $td = Join-Path $schemas "fpml5-0\$view"
    if ((Test-Path $fd410) -and (Test-Path $td)) {
        Run-Differ $fd410 $td "4-10" "5-0" $view
    } else {
        Write-Host "  Skipping 4-10 → 5-0 [$view] (directory missing)" -ForegroundColor DarkYellow
    }
}

# ─────────────────────────────────────────────────────────────
# 3. FpML 5.x consecutive pairs (per view)
# ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== FpML 5.x consecutive pairs (per view) ===" -ForegroundColor Cyan

$v5 = @("5-0","5-1","5-2","5-3","5-4","5-5","5-6","5-7","5-8","5-9","5-10","5-11","5-12","5-13")
for ($i = 0; $i -lt $v5.Count - 1; $i++) {
    $fv  = $v5[$i]; $tv  = $v5[$i+1]
    $fdb = Join-Path $schemas "fpml$fv"
    $tdb = Join-Path $schemas "fpml$tv"

    if (-not (Test-Path $fdb)) {
        Write-Host "  Skipping $fv → $tv (from-dir missing: $fdb)" -ForegroundColor DarkYellow; continue
    }
    if (-not (Test-Path $tdb)) {
        Write-Host "  Skipping $fv → $tv (to-dir missing: $tdb)"   -ForegroundColor DarkYellow; continue
    }

    $fromViews = Get-ChildItem $fdb -Directory | Select-Object -ExpandProperty Name
    $toViews   = Get-ChildItem $tdb -Directory | Select-Object -ExpandProperty Name
    $common    = $fromViews | Where-Object { $toViews -contains $_ }

    if (-not $common) {
        Write-Host "  Skipping $fv → $tv (no common views)" -ForegroundColor DarkYellow; continue
    }

    foreach ($view in $common) {
        $fd = Join-Path $fdb $view
        $td = Join-Path $tdb $view
        Run-Differ $fd $td $fv $tv $view
    }
}

# ─────────────────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " Generation complete: $ok succeeded,  $fail failed  (total: $total)" -ForegroundColor Cyan
Write-Host " Candidate files: $out" -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan




