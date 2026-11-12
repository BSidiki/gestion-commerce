# Construit l'installateur Windows de Gestion Commerce.
#
#   powershell -ExecutionPolicy Bypass -File installer\build.ps1
#   powershell -ExecutionPolicy Bypass -File installer\build.ps1 -Version 0.2.0 -SkipTests
#
# Prérequis sur le poste de construction : JDK 21 (jpackage), Maven, Node.js,
# et Inno Setup 6 pour l'étape finale (https://jrsoftware.org/isinfo.php).
# Sans Inno Setup, le script s'arrête après avoir produit le dossier du programme.

param(
    [string]$Version = '0.1.0',
    [int]$Port = 8787,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $PSScriptRoot 'build'
$stage = Join-Path $build 'stage'
$appName = 'GestionCommerce'

function Invoke-Step([string]$Label, [scriptblock]$Action) {
    Write-Host ''
    Write-Host "== $Label" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "Échec de l'étape : $Label (code $LASTEXITCODE)"
    }
}

foreach ($tool in 'node', 'npm', 'mvn', 'jpackage') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Outil introuvable : $tool. Vérifiez qu'il est installé et présent dans le PATH."
    }
}

# 1. Interface : produit frontend\dist\frontend\browser
Push-Location (Join-Path $root 'frontend')
try {
    Invoke-Step 'Dépendances de l''interface' { npm ci --no-audit --no-fund }
    Invoke-Step 'Compilation de l''interface' { npm run build }
}
finally {
    Pop-Location
}

# 2. Backend : le fichier .jar embarque l'interface compilée à l'étape 1
Push-Location (Join-Path $root 'backend')
try {
    if ($SkipTests) {
        Invoke-Step 'Compilation du programme (sans les tests)' { mvn -B -q clean package -DskipTests }
    }
    else {
        Invoke-Step 'Compilation et tests du programme' { mvn -B -q clean package }
    }
}
finally {
    Pop-Location
}

$jar = Get-ChildItem (Join-Path $root 'backend\target') -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*.original' } |
    Select-Object -First 1
if (-not $jar) {
    throw 'Fichier .jar introuvable dans backend\target.'
}

# 3. Dossier du programme : le .jar, un lanceur .exe et son propre Java réduit
if (Test-Path $build) {
    Remove-Item $build -Recurse -Force
}
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item $jar.FullName (Join-Path $stage 'gestion-commerce.jar')

# Modules Java nécessaires à Spring Boot, SQLite et au chiffrement des sauvegardes.
$modules = 'java.base,java.logging,java.sql,java.naming,java.management,java.xml,java.desktop,' +
    'java.instrument,java.security.jgss,java.net.http,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.charsets'

Invoke-Step 'Assemblage du programme (jpackage)' {
    jpackage `
        --type app-image `
        --name $appName `
        --app-version $Version `
        --vendor 'Gestion Commerce' `
        --input $stage `
        --main-jar 'gestion-commerce.jar' `
        --dest $build `
        --add-modules $modules `
        --jlink-options '--strip-debug --no-header-files --no-man-pages --compress zip-6' `
        --java-options '-Dapp.packaged=true' `
        --java-options "-Dserver.port=$Port" `
        --java-options '-Dfile.encoding=UTF-8' `
        --java-options '-Xmx384m'
}

$appDir = Join-Path $build $appName
Write-Host ''
Write-Host "Dossier du programme : $appDir" -ForegroundColor Green
Write-Host "Pour l'essayer sans l'installer : $appDir\$appName.exe"

# 4. Installateur
$iscc = Get-Command iscc -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $iscc) {
    $folders = @(${env:ProgramFiles(x86)}, $env:ProgramFiles, (Join-Path $env:LOCALAPPDATA 'Programs')) |
        Where-Object { $_ }
    $iscc = $folders |
        ForEach-Object { Join-Path $_ 'Inno Setup 6\ISCC.exe' } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
}
if (-not $iscc) {
    Write-Warning 'Inno Setup 6 est introuvable : installateur non construit. Installez-le puis relancez ce script.'
    exit 0
}

Invoke-Step 'Construction de l''installateur (Inno Setup)' {
    & $iscc "/DAppVersion=$Version" (Join-Path $PSScriptRoot 'gestion-commerce.iss')
}

Write-Host ''
Write-Host "Installateur : $build\$appName-Installation-$Version.exe" -ForegroundColor Green
