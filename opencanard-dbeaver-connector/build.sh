#!/usr/bin/env bash
# Builds org.opencanard.dbeaver.connector_0.1.0.jar, the plugin to drop into DBeaver's dropins folder.
# Build the driver first:  mvn -pl opencanard-jdbc-driver -am clean package
set -euo pipefail
cd "$(dirname "$0")"

DRIVER="$(ls ../opencanard-jdbc-driver/target/opencanard-jdbc-driver-*-all.jar 2>/dev/null | head -1)"
OUT=target
BUNDLE=$OUT/bundle
JAR=$OUT/org.opencanard.dbeaver.connector_0.1.0.jar

rm -rf "$OUT"; mkdir -p "$BUNDLE/drivers"
cp -r META-INF plugin.xml icons "$BUNDLE/"
if [ -n "$DRIVER" ] && [ -f "$DRIVER" ]; then
  # fixed name, referenced by plugin.xml
  cp "$DRIVER" "$BUNDLE/drivers/opencanard-jdbc-driver-all.jar"
  echo "driver jar included: $DRIVER"
else
  echo "WARNING: opencanard-jdbc-driver/target/opencanard-jdbc-driver-*-all.jar not found, the plugin is built without the driver jar (add it in DBeaver's driver manager)" >&2
fi
jar --create --file "$JAR" --manifest "$BUNDLE/META-INF/MANIFEST.MF" -C "$BUNDLE" plugin.xml -C "$BUNDLE" icons -C "$BUNDLE" drivers
echo "built $JAR"
