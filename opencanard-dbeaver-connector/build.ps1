# Builds opencanard-dbeaver-connector_0.1.0.jar, the plugin to drop into DBeaver's dropins folder.
# Build the driver first:  mvn -pl opencanard-jdbc-driver -am clean package
# Needs a JDK on the PATH (the "jar" tool).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$driver = '..\opencanard-jdbc-driver\target\opencanard-jdbc-driver-0.1-all.jar'
$out    = 'target'
$bundle = "$out\bundle"
$jar    = "$out\opencanard-dbeaver-connector_0.1.0.jar"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$bundle\drivers" | Out-Null
Copy-Item -Recurse META-INF, plugin.xml, icons $bundle
if (Test-Path $driver) {
    Copy-Item $driver "$bundle\drivers\"
    Write-Host "driver jar included: $driver"
} else {
    Write-Warning "$driver not found, the plugin is built without the driver jar (add it in DBeaver's driver manager)"
}
jar --create --file $jar --manifest "$bundle\META-INF\MANIFEST.MF" -C $bundle plugin.xml -C $bundle icons -C $bundle drivers
Write-Host "built $jar"
