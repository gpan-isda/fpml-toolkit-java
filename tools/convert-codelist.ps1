param(
    [string]$CodelistDir = "tools\codelist_enhanced_metadata_2_23\codelist_enhanced_metadata_2_23\codelist",
    [string]$OutFile = "files-fpml\data\schemes5-11.generated.xml",
    [switch]$Overwrite
)

if (-not (Test-Path $CodelistDir)) {
    Write-Error "Codelist directory not found: $CodelistDir"
    exit 2
}
$files = Get-ChildItem -Path $CodelistDir -Filter *.xml -File | Sort-Object Name
if ($files.Count -eq 0) {
    Write-Error "No XML files found in $CodelistDir"
    exit 2
}

# Prepare an XmlWriter to build output
$outDir = Split-Path -Path $OutFile -Parent
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
if ((Test-Path $OutFile) -and (-not $Overwrite)) { Write-Error "Refusing to overwrite existing file: $OutFile (use -Overwrite)"; exit 3 }

$settings = New-Object System.Xml.XmlWriterSettings
$settings.Indent = $true
$settings.Encoding = [System.Text.Encoding]::UTF8
$settings.NewLineHandling = "Entitize"

$writer = [System.Xml.XmlWriter]::Create($OutFile, $settings)
$writer.WriteStartDocument()
$writer.WriteStartElement('schemeDefinitions')

$totalFiles = 0
$totalSchemes = 0
$totalRows = 0

foreach ($f in $files) {
    $totalFiles++
    try {
        $str = Get-Content -LiteralPath $f.FullName -Raw
        $xml = [xml]$str
    } catch {
        Write-Warning "Failed to parse file $($f.Name): $_"
        continue
    }

    # try to find Identification node (with or without namespace)
    $ns = $xml.DocumentElement.NamespaceURI
    $ident = $null
    if ($ns) {
        $ident = $xml.SelectSingleNode("/*[local-name()='CodeList']/*[local-name()='Identification']")
    } else {
        $ident = $xml.SelectSingleNode("/CodeList/Identification")
    }
    if (-not $ident) {
        Write-Warning "No Identification node in $($f.Name); skipping"
        continue
    }
    # safely extract Identification children
    $cvn = $ident.SelectSingleNode("*[local-name()='CanonicalVersionUri']")
    $can = $ident.SelectSingleNode("*[local-name()='CanonicalUri']")
    $canonicalUri = if ($cvn -and $cvn.InnerText) { $cvn.InnerText.Trim() } elseif ($can -and $can.InnerText) { $can.InnerText.Trim() } else { $null }
    $canonicalUriSimple = if ($can -and $can.InnerText) { $can.InnerText.Trim() } else { $null }
    $shortNameNode = $ident.SelectSingleNode("*[local-name()='ShortName']")
    $shortName = if ($shortNameNode -and $shortNameNode.InnerText) { $shortNameNode.InnerText.Trim() } else { [System.IO.Path]::GetFileNameWithoutExtension($f.Name) }
    $versionNode = $ident.SelectSingleNode("*[local-name()='Version']")
    $version = if ($versionNode -and $versionNode.InnerText) { $versionNode.InnerText.Trim() } else { $null }

    $schemeUri = if ($canonicalUri) { $canonicalUri } elseif ($canonicalUriSimple) { $canonicalUriSimple } else { "http://www.fpml.org/coding-scheme/" + $shortName }
    $schemeName = if ($version) { "$shortName-$version" } else { $shortName }

    $writer.WriteStartElement('scheme')
    $writer.WriteAttributeString('uri', $schemeUri)
    if ($canonicalUriSimple) { $writer.WriteAttributeString('canonicalUri', $canonicalUriSimple) }
    $writer.WriteAttributeString('name', $schemeName)

    # Find Row elements under SimpleCodeList or CodeListStructure
    $rows = $xml.SelectNodes("//*[local-name()='Row']")
    if ($rows.Count -eq 0) { $rows = $xml.SelectNodes("//*[local-name()='SimpleCodeList']/*[local-name()='Row']") }

    $writer.WriteStartElement('schemeValues')
    foreach ($r in $rows) {
        $totalRows++
        # get SimpleValue children
        $vals = @()
        foreach ($sv in $r.SelectNodes(".//*[local-name()='SimpleValue']")) { $vals += $sv.InnerText.Trim() }
        if ($vals.Count -eq 0) { continue }
        $code = $vals[0]
        $source = if ($vals.Count -gt 1 -and $vals[1]) { $vals[1] } else { 'FpML' }
        $desc = if ($vals.Count -gt 2) { $vals[2] } else { $null }

        $writer.WriteStartElement('schemeValue')
        $writer.WriteAttributeString('schemeValueSource', $source)
        $writer.WriteAttributeString('name', $code)
        if ($desc) {
            $writer.WriteStartElement('paragraph')
            $writer.WriteString($desc)
            $writer.WriteEndElement()
        }
        $writer.WriteEndElement() # schemeValue
        $totalSchemes++
    }
    $writer.WriteEndElement() # schemeValues
    $writer.WriteEndElement() # scheme
}

$writer.WriteEndElement() # schemeDefinitions
$writer.WriteEndDocument()
$writer.Flush()
$writer.Close()

Write-Output "Converted $totalFiles files, produced $totalSchemes schemeValue entries (rows: $totalRows) -> $OutFile"
