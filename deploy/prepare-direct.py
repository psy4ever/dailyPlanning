"""Persist the public IPv4 address without displaying or replacing credentials."""
import ipaddress
import os
from pathlib import Path
import sys
import tempfile


def public_ip(value):
    address = ipaddress.IPv4Address(value)
    if not address.is_global:
        raise ValueError("A globally routable public IPv4 address is required")
    return str(address)


def configure(path, host):
    host = public_ip(host)
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    result = []
    for line in lines:
        if line.strip().startswith("API_PUBLIC_HOST="):
            continue
        result.append(line)
    result.append("API_PUBLIC_HOST=" + host)
    descriptor, temporary = tempfile.mkstemp(prefix=".env-direct-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write("\n".join(result) + "\n")
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 prepare-direct.py PUBLIC_IPV4")
    target = Path.home() / "daily-rhythm" / ".env"
    if not target.is_file():
        raise SystemExit("Missing ~/daily-rhythm/.env; existing MySQL deployment is required")
    configure(target, sys.argv[1])
    print("Public HTTPS host saved; existing database and account settings retained.")
