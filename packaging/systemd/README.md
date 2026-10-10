# systemd packaging

`regpack.service` is the hardened unit for the regpack walking skeleton. It runs
`/opt/regpack/bin/regpack serve` as a transient dynamic user (`DynamicUser=yes`, state in
`/var/lib/regpack`), with no capabilities and a read-only view of the file system. A JVM stopped by
SIGTERM exits with status 143 after its shutdown hook has run, so `SuccessExitStatus=143` makes a
normal stop report `Result=success`.

The unit ships inside the distribution as `share/systemd/regpack.service`.

## Install

Run as root on a host with systemd and a JDK 25:

```bash
mkdir -p /opt/regpack
tar -xzf regpack-0.1.0-SNAPSHOT-dist.tar.gz -C /opt/regpack --strip-components=1 --no-same-owner
chown -R root:root /opt/regpack

install -d /etc/regpack
install -m 0600 -o root -g root /dev/null /etc/regpack/regpack.env
printf 'JAVA_HOME=%s\nREGPACK_HTTP_BIND=127.0.0.1\nREGPACK_HTTP_PORT=%s\n' /path/to/jdk-25 8080 \
  >> /etc/regpack/regpack.env

install -m 0644 /opt/regpack/share/systemd/regpack.service /etc/systemd/system/regpack.service
systemctl daemon-reload
systemctl enable --now regpack
```

`/etc/regpack/regpack.env` is optional (the unit reads it with a leading `-`). It holds `JAVA_HOME`,
`REGPACK_HTTP_BIND` and `REGPACK_HTTP_PORT`; keep it mode 0600 and owned by root.

Check the unit with `systemd-analyze verify /etc/systemd/system/regpack.service` (no output means
no findings) and the service with `journalctl -u regpack`.

## Uninstall

```bash
systemctl disable --now regpack
rm -f /etc/systemd/system/regpack.service /etc/regpack/regpack.env
systemctl daemon-reload
rm -rf /opt/regpack /var/lib/private/regpack /var/lib/regpack
rmdir /etc/regpack 2>/dev/null || true
```

## Smoke run

`smoke.sh` installs the distribution, starts the unit, waits for readiness, runs
`regpack smoke --health-url`, stops the unit and asserts `Result=success` plus the
`regpack lifecycle: stopped cleanly` journal marker. It deletes `/opt/regpack`,
`/etc/regpack/regpack.env` and the installed unit, so it only runs on a disposable host and needs
`--destructive`:

```bash
sudo env "REGPACK_JAVA_HOME=/path/to/jdk-25" bash packaging/systemd/smoke.sh regpack-0.1.0-SNAPSHOT-dist.tar.gz --destructive
```

The JDK is passed as `REGPACK_JAVA_HOME` through `env`, not as a preserved `JAVA_HOME`: on Ubuntu,
PAM re-reads `/etc/environment` after sudo and resets `JAVA_HOME` to the image default, which started
the service on the wrong JVM in the first CI run. The script also refuses a JDK that is not Java 25.
