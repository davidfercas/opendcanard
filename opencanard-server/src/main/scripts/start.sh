#!/bin/bash

# --------------------------------------------------
# OpenCanard Server Startup Script
#
# Optional environment variables:
#   OPENCANARD_XMS        initial heap size          (default: 512m)
#   OPENCANARD_XMX        maximum heap size          (default: 2g)
#   OPENCANARD_JAVA_OPTS  extra JVM options appended to the defaults
#   JAVA_HOME             JDK/JRE to use instead of the one on the PATH
#
# Required environment variables:
#   OPENCANARD_ADMIN_USER      admin user created when the metadata database
#   OPENCANARD_ADMIN_PASSWORD  is created for the first time
# --------------------------------------------------

# Resolve base directory
BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Run from the installation directory: the log file path in
# conf/log4j2.xml is relative to the working directory.
cd "$BASE_DIR" || exit 1

# Java executable
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN="java"
fi

# Classpath
CP="$BASE_DIR/lib/opencanard-server-0.1.jar:$BASE_DIR/lib/external/*"

# JVM options
JAVA_OPTS=""
JAVA_OPTS="$JAVA_OPTS -Dconf.dir=$BASE_DIR/conf"
JAVA_OPTS="$JAVA_OPTS -Dmetadata.dir=$BASE_DIR/metadata"
JAVA_OPTS="$JAVA_OPTS -Ddata.dir=$BASE_DIR/data"
JAVA_OPTS="$JAVA_OPTS -Dlog.dir=$BASE_DIR/logs"
# Without this, log4j does not find conf/log4j2.xml (conf/ is not on the classpath)
# and falls back to its default, which only prints ERROR to the console.
JAVA_OPTS="$JAVA_OPTS -Dlog4j.configurationFile=$BASE_DIR/conf/log4j2.xml"

# Admin credentials (kept in an array so spaces/special characters in the password survive)
ADMIN_OPTS=("-Dadmin.user=$OPENCANARD_ADMIN_USER" "-Dadmin.password=$OPENCANARD_ADMIN_PASSWORD")

# Arrow / JDK module opening
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.nio=ALL-UNNAMED"

# Optional JVM tuning
JAVA_OPTS="$JAVA_OPTS -Xms${OPENCANARD_XMS:-512m}"
JAVA_OPTS="$JAVA_OPTS -Xmx${OPENCANARD_XMX:-2g}"
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS $OPENCANARD_JAVA_OPTS"

# Create runtime directories if missing
mkdir -p "$BASE_DIR/data"
mkdir -p "$BASE_DIR/metadata"
mkdir -p "$BASE_DIR/logs"

# Start application
exec "$JAVA_BIN" $JAVA_OPTS "${ADMIN_OPTS[@]}" -cp "$CP" io.opencanard.server.OpenCanardServer
