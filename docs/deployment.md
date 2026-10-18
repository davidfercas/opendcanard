# Deployment

Two ways to run OpenCanard as a long-lived service: a systemd service on Red Hat family distributions,
or a Docker container.

> This version has **no TLS** and the Flight port does not validate tokens properly yet. Do not publish
> ports `8815` and `8084` to networks you do not trust. See
> [Authentication and security](authentication.md).

Both options need the same first step after the server is up: create the users table and an
administrator, as described in [Authentication and security](authentication.md#creating-users).

## Red Hat, Rocky, AlmaLinux (systemd)

Files are in [`deploy/redhat/`](../deploy/redhat):

| File | Purpose |
| --- | --- |
| `install.sh` | Installs or upgrades OpenCanard from the distribution zip. |
| `opencanard.service` | systemd unit installed to `/etc/systemd/system/`. |

### Install

Build the distribution on any machine with JDK 17 and Maven, copy `opencanard.zip` and the
`deploy/redhat` directory to the server, then run as root:

```bash
mvn package
sudo deploy/redhat/install.sh opencanard-server/target/opencanard.zip
```

The script:

1. installs `java-17-openjdk-headless` and `unzip` with `dnf` if they are missing;
2. creates the system user `opencanard` (no login shell);
3. installs the server into `/opt/opencanard`;
4. installs the systemd unit, creates `/etc/sysconfig/opencanard`, enables the service and starts it
   (use `--no-start` to only enable it).

It does not open any firewall port. When you are ready, and only for trusted networks:

```bash
sudo firewall-cmd --permanent --add-port=8815/tcp --add-port=8084/tcp
sudo firewall-cmd --reload
```

### Operate

```bash
systemctl status opencanard
journalctl -u opencanard -f
sudo systemctl restart opencanard
```

| What | Where |
| --- | --- |
| Server configuration | `/opt/opencanard/conf/opencanard.properties` (restart after editing) |
| Heap size and JVM options | `/etc/sysconfig/opencanard` (`OPENCANARD_XMS`, `OPENCANARD_XMX`, `OPENCANARD_JAVA_OPTS`, `JAVA_HOME`) |
| Application log | `/opt/opencanard/logs/opencanard.log` |
| Databases | `/opt/opencanard/data`, `/opt/opencanard/metadata` |

### Upgrade

Run `install.sh` again with the new zip. `bin/` and `lib/` are replaced; `conf/`, `data/`, `metadata/`
and `logs/` are kept, and the service is restarted if it was running.

### Running the script by hand

The same `bin/start.sh` works without systemd (`/opt/opencanard/bin/start.sh`). It reads the same
`OPENCANARD_*` environment variables.

## Docker

The [`Dockerfile`](../Dockerfile) at the repository root builds the server inside a Maven container
(JDK 17) and copies the result into a slim `eclipse-temurin:17-jre` image. The server runs as the
non-root user `opencanard` (UID/GID `10001`).

```bash
docker build -t opencanard .

docker run -d --name opencanard \
  -p 8815:8815 -p 8084:8084 \
  -v opencanard-data:/opt/opencanard/data \
  -v opencanard-metadata:/opt/opencanard/metadata \
  opencanard
```

| Item | Details |
| --- | --- |
| Ports | `8815` Arrow Flight, `8084` REST API. |
| Volumes | `/opt/opencanard/data`, `/opt/opencanard/metadata`, `/opt/opencanard/logs`. Keep at least the first two, or the databases (and the users) are lost with the container. |
| Memory | `-e OPENCANARD_XMX=4g` (default `2g`) and `-e OPENCANARD_XMS=1g` (default `512m`). Give the container more memory than the heap, for example `--memory 5g`. |
| Extra JVM options | `-e OPENCANARD_JAVA_OPTS="..."`. |
| Configuration | The defaults are baked into the image. To change them, bind-mount a directory containing `opencanard.properties` and `log4j2.xml` over `/opt/opencanard/conf`. Copy both files from `opencanard-server/src/conf/` first, because a mounted directory replaces the defaults. |
| Health | The image has a `HEALTHCHECK` that calls `GET /health` on port `8084`. If you change `opencanard.rest.port`, override it. |

Notes:

- **Bind mounts and permissions.** The server writes as UID `10001`. With a host directory instead of a
  named volume, run `sudo chown -R 10001:10001 <dir>` first.
- **First start needs Internet access.** The server runs `INSTALL arrow;` and DuckDB downloads the
  extension into `/opt/opencanard/.duckdb`. It is downloaded again for every new container unless you
  also mount a volume there.
- **Do not switch the base image to Alpine.** DuckDB and Arrow ship glibc native libraries.
- On SELinux hosts (RHEL with Podman or Docker), add `:Z` to bind mounts, for example
  `-v /srv/opencanard/data:/opt/opencanard/data:Z`.
- Podman works with the same commands (`podman build`, `podman run`).
