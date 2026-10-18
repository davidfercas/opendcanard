#!/bin/bash
# --------------------------------------------------
# Installs OpenCanard as a systemd service on Red Hat family
# distributions (RHEL, Rocky Linux, AlmaLinux, CentOS Stream, Fedora).
#
# Usage (as root):
#   ./install.sh /path/to/opencanard.zip [--no-start]
#
# Re-running it with a newer zip upgrades the installation. The
# conf/, data/, metadata/ and logs/ directories are never overwritten.
# --------------------------------------------------
set -euo pipefail

INSTALL_DIR="/opt/opencanard"
SERVICE_USER="opencanard"
SERVICE_NAME="opencanard"
SYSCONFIG_FILE="/etc/sysconfig/${SERVICE_NAME}"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ZIP_FILE=""
START_SERVICE=1

for arg in "$@"; do
    case "$arg" in
        --no-start) START_SERVICE=0 ;;
        -h|--help)  sed -n '2,12p' "$0"; exit 0 ;;
        *)          ZIP_FILE="$arg" ;;
    esac
done

if [ "$(id -u)" -ne 0 ]; then
    echo "This script must be run as root (try: sudo $0 ...)" >&2
    exit 1
fi

if [ -z "$ZIP_FILE" ] || [ ! -f "$ZIP_FILE" ]; then
    echo "Usage: $0 /path/to/opencanard.zip [--no-start]" >&2
    echo "Build the zip first with: mvn package  (opencanard-server/target/opencanard.zip)" >&2
    exit 1
fi

PKG_MGR="dnf"
command -v dnf >/dev/null 2>&1 || PKG_MGR="yum"

# --- 1. Java 17+ and unzip -------------------------------------------
java_major() {
    local v
    v="$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    echo "${v:-0}"
}

if ! command -v java >/dev/null 2>&1 || [ "$(java_major java)" -lt 17 ]; then
    echo ">> Installing OpenJDK 17"
    "$PKG_MGR" install -y java-17-openjdk-headless
fi
command -v unzip >/dev/null 2>&1 || "$PKG_MGR" install -y unzip

# --- 2. Service user ---------------------------------------------------
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
    echo ">> Creating system user '$SERVICE_USER'"
    useradd --system --home-dir "$INSTALL_DIR" --no-create-home \
            --shell /sbin/nologin "$SERVICE_USER"
fi

# --- 3. Files ------------------------------------------------------------
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
unzip -q "$ZIP_FILE" -d "$TMP_DIR"

# The zip contains a single top-level "opencanard" directory
SRC_DIR="$TMP_DIR/opencanard"
if [ ! -d "$SRC_DIR/lib" ]; then
    echo "Unexpected zip layout: $SRC_DIR/lib not found" >&2
    exit 1
fi

UPGRADE=0
[ -d "$INSTALL_DIR/lib" ] && UPGRADE=1

if [ "$UPGRADE" -eq 1 ] && systemctl is-active --quiet "$SERVICE_NAME"; then
    echo ">> Stopping running service for the upgrade"
    systemctl stop "$SERVICE_NAME"
    RESTART_AFTER=1
else
    RESTART_AFTER=0
fi

echo ">> Installing into $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"

# Replaced on every install / upgrade
rm -rf "$INSTALL_DIR/bin" "$INSTALL_DIR/lib"
cp -a "$SRC_DIR/bin" "$INSTALL_DIR/bin"
cp -a "$SRC_DIR/lib" "$INSTALL_DIR/lib"
chmod 0755 "$INSTALL_DIR"/bin/*.sh

# Configuration is only created the first time
if [ ! -d "$INSTALL_DIR/conf" ]; then
    cp -a "$SRC_DIR/conf" "$INSTALL_DIR/conf"
else
    echo ">> Keeping existing conf/ (not overwritten)"
fi

mkdir -p "$INSTALL_DIR/data" "$INSTALL_DIR/metadata" "$INSTALL_DIR/logs"
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
# Databases and configuration may hold credentials and hashes: no access for others
chmod 0750 "$INSTALL_DIR/conf" "$INSTALL_DIR/data" "$INSTALL_DIR/metadata"

# --- 4. systemd unit and sysconfig ------------------------------------------
install -m 0644 "$SCRIPT_DIR/opencanard.service" "$UNIT_FILE"

if [ ! -f "$SYSCONFIG_FILE" ]; then
    cat > "$SYSCONFIG_FILE" <<'EOF'
# Environment for the opencanard systemd service
# Heap size of the JVM
#OPENCANARD_XMS=512m
#OPENCANARD_XMX=2g
# Extra JVM options
#OPENCANARD_JAVA_OPTS=
# Use a specific JDK instead of the one on the PATH
#JAVA_HOME=/usr/lib/jvm/java-17-openjdk
EOF
    chmod 0644 "$SYSCONFIG_FILE"
fi

# SELinux: make sure the files carry the right labels (no-op if SELinux is off)
if command -v restorecon >/dev/null 2>&1; then
    restorecon -R "$INSTALL_DIR" "$UNIT_FILE" 2>/dev/null || true
fi

systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null

if [ "$START_SERVICE" -eq 1 ] || [ "$RESTART_AFTER" -eq 1 ]; then
    systemctl restart "$SERVICE_NAME"
    echo ">> Service started"
fi

cat <<EOF

OpenCanard installed in $INSTALL_DIR

  Status:   systemctl status $SERVICE_NAME
  Logs:     journalctl -u $SERVICE_NAME -f     (and $INSTALL_DIR/logs/opencanard.log)
  Config:   $INSTALL_DIR/conf/opencanard.properties  (then: systemctl restart $SERVICE_NAME)
  JVM opts: $SYSCONFIG_FILE

Next steps:
  * Create the users table and the first administrator (see docs/authentication.md).
  * Ports 8815 (Arrow Flight) and 8084 (REST) are NOT opened in the firewall. This version has
    no TLS, so only open them to trusted networks, for example:
      firewall-cmd --permanent --add-port=8815/tcp --add-port=8084/tcp && firewall-cmd --reload
EOF
