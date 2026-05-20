#!/bin/bash

# --------------------------------------------------
# OpenDuck Server Startup Script
# --------------------------------------------------

# Resolve base directory
BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Classpath
CP="$BASE_DIR/lib/openduck-0.1.jar:$BASE_DIR/lib/external/*"

# JVM options
JAVA_OPTS=""
JAVA_OPTS="$JAVA_OPTS -Dconf.dir=$BASE_DIR/conf"
JAVA_OPTS="$JAVA_OPTS -Dmetadata.dir=$BASE_DIR/metadata"
JAVA_OPTS="$JAVA_OPTS -Ddata.dir=$BASE_DIR/data"
JAVA_OPTS="$JAVA_OPTS -Dlog.dir=$BASE_DIR/logs"

# Arrow / JDK module opening
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.nio=ALL-UNNAMED"

# Optional JVM tuning
JAVA_OPTS="$JAVA_OPTS -Xms512m"
JAVA_OPTS="$JAVA_OPTS -Xmx2g"
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"

# Create runtime directories if missing
mkdir -p "$BASE_DIR/data"
mkdir -p "$BASE_DIR/metadata"
mkdir -p "$BASE_DIR/logs"

# Start application
exec java $JAVA_OPTS -cp "$CP" io.openduck.server.OpenDuckServer