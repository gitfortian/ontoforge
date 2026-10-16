"""External test IdP credentials only; no login bypass in the shipped frontend."""
import json
import os
from pathlib import Path

CONFIG = json.loads(Path(os.environ["E2E_AUTH_FILE"]).read_text(encoding="utf-8"))
PACKAGES = iter(CONFIG["packages"][:60])  # last three reserved for PowerShell acceptance


def headers(role="editor"):
    return {"Authorization": "Bearer " + CONFIG[role + "Token"]}


def assigned_package():
    return next(PACKAGES)
