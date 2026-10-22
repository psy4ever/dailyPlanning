"""Consistent MySQL dump -> gzip -> encrypted, authenticated backup on Ubuntu.

Run from ~/daily-rhythm. Keep .backup-key separately from offsite ciphertext.
"""
import gzip
import hashlib
import hmac
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time


def digest(path: Path, key: bytes) -> str:
    mac = hmac.new(hashlib.sha256(b"daily-rhythm-backup-auth-v1:" + key).digest(), digestmod=hashlib.sha256)
    mac.update(b"daily-rhythm-backup-v1\0")
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            mac.update(block)
    return mac.hexdigest()


def decrypt(archive: Path, key: Path, output: Path):
    expected = archive.with_suffix(archive.suffix + ".hmac").read_text().strip()
    if not hmac.compare_digest(expected, digest(archive, key.read_bytes())):
        raise ValueError("Backup authentication failed; no data was restored")
    subprocess.run(["openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "600000",
                    "-pass", "file:" + str(key), "-in", str(archive), "-out", str(output)], check=True)


def main():
    os.umask(0o077)
    base = Path.cwd()
    if not (base / "compose.yaml").is_file() or not (base / ".env").is_file():
        raise SystemExit("Run inside ~/daily-rhythm with the existing compose.yaml and .env")
    folder = base / "backups"
    folder.mkdir(mode=0o700, exist_ok=True)
    key = base / ".backup-key"
    if not key.exists():
        with key.open("x", encoding="ascii") as output:
            output.write(secrets.token_hex(32))
    os.chmod(key, 0o600)
    name = "daily-rhythm-" + time.strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(4)
    temporary = folder / (name + ".partial")
    archive = folder / (name + ".enc")
    try:
        with temporary.open("xb") as encrypted:
            crypt = subprocess.Popen(["openssl", "enc", "-aes-256-cbc", "-salt", "-pbkdf2", "-iter", "600000",
                                      "-pass", "file:" + str(key)], stdin=subprocess.PIPE, stdout=encrypted)
            dump = subprocess.Popen(["sudo", "docker", "compose", "exec", "-T", "db", "sh", "-c",
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE"'], stdout=subprocess.PIPE)
            try:
                with gzip.GzipFile(fileobj=crypt.stdin, mode="wb") as compressed:
                    for block in iter(lambda: dump.stdout.read(1024 * 1024), b""):
                        compressed.write(block)
                dump.stdout.close()
                crypt.stdin.close()
                if dump.wait() != 0 or crypt.wait() != 0:
                    raise RuntimeError("Database dump or encryption failed")
            finally:
                if dump.poll() is None: dump.kill(); dump.wait()
                if crypt.poll() is None: crypt.kill(); crypt.wait()
        temporary.rename(archive)
        archive.with_suffix(".enc.hmac").write_text(digest(archive, key.read_bytes()) + "\n", encoding="ascii")
        # Exact generated names only. Keep at least the newest seven complete backup pairs.
        complete = sorted((file for file in folder.glob("daily-rhythm-*.enc") if file.with_suffix(".enc.hmac").is_file()),
                          key=lambda file: file.stat().st_mtime, reverse=True)
        for old in complete[7:]:
            if old.with_suffix(".enc.hmac").is_file():
                old.unlink(); old.with_suffix(".enc.hmac").unlink()
        print("Backup created:", archive)
        print("Copy .enc + .enc.hmac off this server. Keep .backup-key separately and private.")
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
