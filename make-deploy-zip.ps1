# AeroVisit: build the Elastic Beanstalk deploy zip for the web app (aerovisit-docker)
#
# Run from the AeroVisit_Project folder in PowerShell:
#     powershell -ExecutionPolicy Bypass -File .\make-deploy-zip.ps1            (skips tests: run them first)
#     powershell -ExecutionPolicy Bypass -File .\make-deploy-zip.ps1 -RunTests  (runs the test suite first)
#
# Makes aerovisit-docker-v<next number>.zip next to this script with exactly three entries at the top level:
#     Dockerfile
#     aerovisit.jar                              (target/aerovisit-0.0.1-SNAPSHOT.jar, renamed)
#     .platform/nginx/conf.d/upload_size.conf    (without it nginx falls back to 1 MB uploads and photos fail with 413)
# Entry names use forward slashes (Linux), which PowerShell 5's Compress-Archive does not guarantee.

param([switch]$RunTests)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$app  = Join-Path $root 'aerovisit'

# 1. Build the jar
Push-Location $app
try {
    $started = Get-Date
    if ($RunTests) { & .\mvnw.cmd -q package } else { & .\mvnw.cmd -q package -DskipTests }
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit code $LASTEXITCODE). Nothing was zipped." }
} finally {
    Pop-Location
}

$jar = Join-Path $app 'target\aerovisit-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw "Jar not found: $jar" }
if ((Get-Item $jar).LastWriteTime -lt $started) { throw "The jar was not rebuilt (it is older than this run). Nothing was zipped." }

$files = [ordered]@{
    'Dockerfile'                              = Join-Path $app 'Dockerfile'
    'aerovisit.jar'                           = $jar
    '.platform/nginx/conf.d/upload_size.conf' = Join-Path $app '.platform\nginx\conf.d\upload_size.conf'
}
foreach ($source in $files.Values) {
    if (-not (Test-Path $source)) { throw "Missing file for the zip: $source" }
}

# 2. Next version number after the existing aerovisit-docker-v<N>.zip files
$last = Get-ChildItem -Path $root -Filter 'aerovisit-docker-v*.zip' |
    ForEach-Object { if ($_.BaseName -match '^aerovisit-docker-v(\d+)$') { [int]$Matches[1] } } |
    Measure-Object -Maximum
$next = if ($last.Count -gt 0) { $last.Maximum + 1 } else { 1 }
$zipPath = Join-Path $root "aerovisit-docker-v$next.zip"

# 3. Zip with forward-slash entry names
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in $files.Keys) {
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $files[$entry], $entry, [System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally {
    $zip.Dispose()
}

$sizeMb = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
Write-Host ""
Write-Host "Done: $zipPath ($sizeMb MB)" -ForegroundColor Green
Write-Host "Contents: Dockerfile, aerovisit.jar, .platform/nginx/conf.d/upload_size.conf"
Write-Host "Next: Elastic Beanstalk > aerovisit-docker > Upload and deploy this zip."
