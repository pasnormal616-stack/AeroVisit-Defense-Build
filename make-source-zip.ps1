# AeroVisit: build the editable SOURCE CODE zip for the handover
#
# Run in PowerShell:
#     powershell -ExecutionPolicy Bypass -File "C:\Users\nico\Desktop\AeroVisit_Project\make-source-zip.ps1"
#
# Makes Handover\AeroVisit-source-<date>.zip: everything a developer needs to open, change, test, build and deploy
# AeroVisit, and nothing else. It works from a list of what belongs in it (not a list of what to leave out), so new
# private files are never swept in by accident.
#
# IN:   aerovisit\ (Java source, tests, pom.xml, Maven wrapper, Dockerfile, .platform), AeroVisit-AI\ (Python source,
#       tests, face models, requirements, .platform), the .env.example templates, docs\, the README and guides,
#       aerovisit-schema.sql, make-deploy-zip.ps1, this script, the CI workflow.
# OUT:  the real .env files (passwords and keys), target\ build output, uploads\ (visitors' photos), old deploy zips,
#       backups, .git history, editor and AI-tool folders, __pycache__, *.bak and *.log files, thesis drafts.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# --- What goes in (paths relative to AeroVisit_Project) ------------------------------------------------------------
$include = @(
    'README.md', 'ONBOARDING.md', 'TECHNICAL_SPECIFICATION.md', 'FUNCTIONAL_SPECIFICATION.md',
    'AirForce-Deployment-Guide.md', 'aerovisit-schema.sql', 'make-deploy-zip.ps1', 'make-source-zip.ps1',
    '.gitignore', 'docs', '.github\workflows',

    'aerovisit\src', 'aerovisit\.mvn', 'aerovisit\.platform', 'aerovisit\pom.xml', 'aerovisit\mvnw',
    'aerovisit\mvnw.cmd', 'aerovisit\Dockerfile', 'aerovisit\.env.example', 'aerovisit\.gitignore',
    'aerovisit\.gitattributes', 'aerovisit\TODOS.md',

    'AeroVisit-AI\main.py', 'AeroVisit-AI\kyc.py', 'AeroVisit-AI\requirements.txt', 'AeroVisit-AI\Procfile',
    'AeroVisit-AI\.python-version', 'AeroVisit-AI\.env.example', 'AeroVisit-AI\.platform', 'AeroVisit-AI\models',
    'AeroVisit-AI\evaluation', 'AeroVisit-AI\test_*.py'
)

# Never packed, even inside an included folder
function Test-Excluded([string]$relative) {
    $name = Split-Path $relative -Leaf
    if ($name -eq '.env') { return $true }                                   # real secrets (.env.example is fine)
    if ($name -like '*.zip' -or $name -like '*.bak*' -or $name -like '*.log') { return $true }
    foreach ($part in $relative.Split('\')) {
        if (@('target', '__pycache__', 'uploads', 'node_modules', '.idea') -contains $part) { return $true }
    }
    return $false
}

# --- Collect the files -------------------------------------------------------------------------------------------
$files = New-Object System.Collections.Generic.List[string]
$missing = @()
foreach ($pattern in $include) {
    $matched = @(Get-Item -Path (Join-Path $root $pattern) -Force -ErrorAction SilentlyContinue)
    if ($matched.Count -eq 0) { $missing += $pattern; continue }
    foreach ($item in $matched) {
        $found = if ($item.PSIsContainer) { Get-ChildItem -Path $item.FullName -Recurse -File -Force } else { @($item) }
        foreach ($f in $found) {
            $relative = $f.FullName.Substring($root.Length).TrimStart('\')
            if (-not (Test-Excluded $relative) -and -not $files.Contains($relative)) { $files.Add($relative) }
        }
    }
}
if ($missing.Count -gt 0) { Write-Host ("Not found, skipped: " + ($missing -join ', ')) -ForegroundColor Yellow }

# Safety net: refuse to pack anything that looks like a real secret file
$secret = $files | Where-Object { (Split-Path $_ -Leaf) -eq '.env' }
if ($secret) { throw "A real .env file was about to be packed: $secret. Nothing was zipped." }

# --- Zip (forward-slash entry names, inside one top-level AeroVisit folder) ---------------------------------------
$outDir = Join-Path $root 'Handover'
New-Item -ItemType Directory -Force $outDir | Out-Null
$zipPath = Join-Path $outDir ("AeroVisit-source-" + (Get-Date -Format 'yyyy-MM-dd') + ".zip")
if (Test-Path $zipPath) { Remove-Item $zipPath }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($relative in $files) {
        $entry = 'AeroVisit/' + ($relative -replace '\\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, (Join-Path $root $relative), $entry, [System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally {
    $zip.Dispose()
}

$sizeMb = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
Write-Host ""
Write-Host "Done: $zipPath ($($files.Count) files, $sizeMb MB)" -ForegroundColor Green
Write-Host "No .env files, uploads, build output, old zips or .git history inside."
