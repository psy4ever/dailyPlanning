#!/usr/bin/env python3
"""Merge the documented Tencent Cloud internal Docker Hub mirror into daemon.json."""

import json
from pathlib import Path
import shutil
import time

MIRROR = "https://mirror.ccs.tencentyun.com"


def configure(config_path: Path):
    config = json.loads(config_path.read_text()) if config_path.exists() else {}
    if not isinstance(config, dict):
        raise ValueError("Docker configuration must be a JSON object")
    mirrors = config.get("registry-mirrors", [])
    if not isinstance(mirrors, list) or not all(isinstance(item, str) for item in mirrors):
        raise ValueError("registry-mirrors must be an array of strings")
    config["registry-mirrors"] = list(dict.fromkeys([MIRROR] + mirrors))
    backup = None
    if config_path.exists():
        backup = Path(str(config_path) + ".bak-" + str(time.time_ns()))
        shutil.copy2(config_path, backup)
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(json.dumps(config, indent=2) + "\n")
    return backup


if __name__ == "__main__":
    backup_path = configure(Path("/etc/docker/daemon.json"))
    if backup_path:
        print("Backup:", backup_path)
    print("Docker mirror configuration saved. Validate it before restarting Docker.")
