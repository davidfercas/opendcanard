#!/usr/bin/env bash
# Builds org.opencanard.dbeaver.connector_0.1.0.jar, the plugin to drop into DBeaver's dropins folder.
# Build the driver first:  mvn -pl opencanard-jdbc-driver -am clean package
set -euo pipefail
cd "$(dirname "$0")"

DRIVER=../opencanard-jdbc-driver/target/opencanard-jdbc-driver-0.1-all.jar
OUT=target
BUNDLE=$OUT/bundle
JAR=$OUT/org.opencanard.dbeaver.connector_0.1.0.jar

rm -rf "$OUT"; mkdir -p "$BUNDLE/drivers"
cp -r META-INF plugin.xml icons "$BUNDLE/"
if [ -f "$DRIVER" ]; then
  cp "$DRIVER" "$BUNDLE/drivers/"
  echo "driver jar included: $DRIVER"
else
  echo "WARNING: $DRIVER not found, the plugin is built without the driver jar (add it in DBeaver's driver manager)" >&2
fi
jar --create --file "$JAR" --manifest "$BUNDLE/META-INF/MANIFEST.MF" -C "$BUNDLE" plugin.xml -C "$BUNDLE" icons -C "$BUNDLE" drivers
echo "built $JAR"
