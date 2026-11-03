# Installs the OpenCanard plugin straight into a DBeaver installation (use this when the "dropins" folder is
# not picked up). It copies the jar to <DBeaver>\plugins and registers it in
# <DBeaver>\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info.
#
#   .\install.ps1                                   # DBeaver in %LOCALAPPDATA%\DBeaver
#   .\install.ps1 -DBeaverDir "C:\Program Files\DBeaver"
#   .\install.ps1 -Uninstall
#
# Close DBeaver first. Afterwards start it once with:  dbeaver.exe -clean
param(
    [string]$DBeaverDir = (Join-Path $env:LOCALAPPDATA 'DBeaver'),
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'

$id      = 'org.opencanard.dbeaver.connector'
$version = '0.1.0'
$jarName = "${id}_$version.jar"
$source  = Join-Path $PSScriptRoot "target\$jarName"
$plugins = Join-Path $DBeaverDir 'plugins'
$info    = Join-Path $DBeaverDir 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
$line    = "$id,$version,plugins/$jarName,4,false"

if (-not (Test-Path $info)) { throw "bundles.info not found: $info (is -DBeaverDir the folder with dbeaver.exe?)" }
if (Get-Process dbeaver -ErrorAction SilentlyContinue) { throw 'DBeaver is running, close it first.' }

# Remove any earlier registration of this plugin
$lines = Get-Content $info | Where-Object { $_ -notlike "$id,*" }

if ($Uninstall) {
    Copy-Item $info "$info.opencanard.bak" -Force
    Set-Content -Path $info -Value $lines -Encoding ASCII
    Remove-Item (Join-Path $plugins "${id}_*.jar") -Force -ErrorAction SilentlyContinue
    Write-Host 'Uninstalled. Start DBeaver with -clean.'
    return
}

if (-not (Test-Path $source)) { throw "$source not found, run .\build.ps1 first" }
Copy-Item $source (Join-Path $plugins $jarName) -Force
Copy-Item $info "$info.opencanard.bak" -Force          # backup of the original file
Set-Content -Path $info -Value ($lines + $line) -Encoding ASCII
Write-Host "Installed $jarName in $plugins"
Write-Host "Registered in $info (backup: $info.opencanard.bak)"
Write-Host 'Now start DBeaver once with:  dbeaver.exe -clean'
