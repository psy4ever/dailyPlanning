"""Run as the Ubuntu SSH user; generate credentials without printing them."""

import os
from pathlib import Path
import secrets
import sys


def prepare(directory: Path) -> bool:
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / ".env"
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        print("Existing .env retained; passwords were NOT changed.")
        return False
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
        output.write("MYSQL_ROOT_PASSWORD=" + secrets.token_hex(32) + "\n")
        output.write("MYSQL_PASSWORD=" + secrets.token_hex(32) + "\n")
    print("Created .env; credentials were not displayed.")
    return True


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else Path.home() / "daily-rhythm")
