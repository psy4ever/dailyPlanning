"""Run interactively on Ubuntu. Preserve existing database credentials."""
import getpass
import os
from pathlib import Path
import sys

path = Path.home() / "daily-rhythm" / ".env"
if not path.is_file():
    raise SystemExit("Missing ~/daily-rhythm/.env; prepare MySQL first")
text = path.read_text(encoding="utf-8")
keys = {line.split("=", 1)[0] for line in text.splitlines() if "=" in line}
if {"DAILY_USERNAME", "DAILY_PASSWORD"} <= keys:
    print("Existing API credentials retained; no changes made.")
    sys.exit(0)
if "DAILY_USERNAME" in keys or "DAILY_PASSWORD" in keys:
    raise SystemExit("Incomplete API credentials; inspect .env privately before continuing.")
username = input("Initial nickname (UID is assigned automatically): ").strip()
if not username or len(username) > 40 or not all(char.isascii() and (char.isalnum() or char in "_-") for char in username):
    raise SystemExit("Use 1-40 ASCII letters/digits or underscore/hyphen for the initial nickname")
password = getpass.getpass("App password (8-16 ASCII characters; hidden): ")
if not 8 <= len(password) <= 16 or not password.isascii() or any(char.isspace() or char in "'\"$#\\" for char in password):
    raise SystemExit("Use 8-16 ASCII characters without whitespace, quotes, dollar, hash or backslash")
if password != getpass.getpass("Repeat password: "):
    raise SystemExit("Passwords differ; no changes made")
os.chmod(path, 0o600)
with path.open("a", encoding="utf-8", newline="\n") as output:
    output.write(("" if text.endswith("\n") else "\n") + "DAILY_USERNAME=" + username + "\nDAILY_PASSWORD=" + password + "\n")
print("Initial App account saved. The server will assign its eight-digit UID; password was not displayed.")
