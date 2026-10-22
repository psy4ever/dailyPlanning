"""Restore into isolated temporary containers; never write to the production database."""
import gzip
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
from backup import decrypt

MYSQL_IMAGE = "mysql:8.4@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364"


def docker(*args, **kwargs):
    return subprocess.run(["sudo", "docker", *args], check=True, **kwargs)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 restore-check.py backups/<file>.enc")
    os.umask(0o077)
    base = Path.cwd()
    archive = Path(sys.argv[1]).resolve()
    key = base / ".backup-key"
    credentials = dict(line.split("=", 1) for line in (base / ".env").read_text().splitlines() if "=" in line and not line.startswith("#"))
    if not credentials.get("DAILY_USERNAME") or not credentials.get("DAILY_PASSWORD"):
        raise SystemExit("App account configuration required")
    suffix = secrets.token_hex(8)
    db_name = "daily-restore-db-" + suffix
    api_name = "daily-restore-api-" + suffix
    network = "daily-restore-network-" + suffix
    network_created = False
    with tempfile.TemporaryDirectory(prefix=".restore-", dir=base) as folder:
        work = Path(folder)
        compressed = work / "dump.sql.gz"
        decrypt(archive, key, compressed)  # Authenticate BEFORE creating containers.
        env = work / "restore.env"
        env.write_text("MYSQL_ROOT_PASSWORD=" + secrets.token_hex(32) + "\nMYSQL_DATABASE=daily_rhythm\nMYSQL_USER=daily_rhythm_app\n"
            + "MYSQL_PASSWORD=" + credentials["MYSQL_PASSWORD"] + "\nDAILY_USERNAME=" + credentials["DAILY_USERNAME"]
            + "\nDAILY_PASSWORD=" + credentials["DAILY_PASSWORD"] + "\nVERIFY_RESTORED=true\nDB_URL=jdbc:mysql://"
            + db_name + ":3306/daily_rhythm?connectionTimeZone=UTC&characterEncoding=UTF-8\n", encoding="utf-8")
        try:
            docker("network", "create", "--internal", network, stdout=subprocess.DEVNULL)
            network_created = True
            docker("run", "-d", "--name", db_name, "--network", network, "--env-file", str(env), MYSQL_IMAGE, stdout=subprocess.DEVNULL)
            mysql = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP -h127.0.0.1 -uroot "$MYSQL_DATABASE"'
            for attempt in range(120):
                ready = subprocess.run(["sudo", "docker", "exec", db_name, "sh", "-c", mysql + " -e 'SELECT 1'"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if ready.returncode == 0: break
                time.sleep(2)
            else: raise RuntimeError("Isolated restore MySQL did not become ready")
            restore = subprocess.Popen(["sudo", "docker", "exec", "-i", db_name, "sh", "-c", mysql], stdin=subprocess.PIPE)
            try:
                with gzip.open(compressed, "rb") as source:
                    for block in iter(lambda: source.read(1024 * 1024), b""): restore.stdin.write(block)
                restore.stdin.close()
                if restore.wait() != 0: raise RuntimeError("SQL restore failed")
            finally:
                if restore.poll() is None: restore.kill(); restore.wait()
            docker("exec", db_name, "sh", "-c", mysql + " -e 'SELECT kind,COUNT(*) AS restored_records FROM cloud_records GROUP BY kind;'")
            docker("run", "-d", "--name", api_name, "--network", network, "--env-file", str(env), "daily-rhythm-api:0.2.4", stdout=subprocess.DEVNULL)
            for attempt in range(90):
                ready = subprocess.run(["sudo", "docker", "exec", api_name, "java", "-cp", "/app/health", "com.dayoudu.cloud.HealthProbe", "authenticated"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if ready.returncode == 0: break
                time.sleep(2)
            else:
                docker("logs", "--tail", "40", api_name)
                raise RuntimeError("Restored API validation/authentication did not pass")
            print("Isolated restore, aggregate relationships, authentication and API read passed.")
        finally:
            # These names are generated above; no production container or volume is targeted.
            for name in (api_name, db_name):
                subprocess.run(["sudo", "docker", "rm", "-f", "-v", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if network_created:
                subprocess.run(["sudo", "docker", "network", "rm", network], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
