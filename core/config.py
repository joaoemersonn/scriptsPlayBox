import os
from pathlib import Path
from typing import Optional


def load_env_file(dotenv_path: Optional[str] = None) -> None:
    # Try given path, then `core/.env`, then project root `../.env`
    candidates = []
    if dotenv_path:
        candidates.append(Path(dotenv_path))
    base = Path(__file__).resolve().parent
    candidates.append(base / ".env")
    # project root (one level up)
    candidates.append(base.parent / ".env")

    for candidate in candidates:
        try:
            with open(candidate, "r", encoding="utf-8") as env_file:
                for line in env_file:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, value = line.split("=", 1)
                    key = key.strip()
                    value = value.strip().strip("'\"")
                    if key and key not in os.environ:
                        os.environ[key] = value
            # stop after successfully loading a file
            return
        except FileNotFoundError:
            continue


load_env_file()

VENDPROMAX_EMAIL = os.getenv("VENDPROMAX_EMAIL", "")
VENDPROMAX_PASSWORD = os.getenv("VENDPROMAX_PASSWORD", "")

PAYXYZ_EMAIL = os.getenv("PAYXYZ_EMAIL", "")
PAYXYZ_PASSWORD = os.getenv("PAYXYZ_PASSWORD", "")

ZAPIZI_EMAIL = os.getenv("ZAPIZI_EMAIL", "")
ZAPIZI_PASSWORD = os.getenv("ZAPIZI_PASSWORD", "")
ZAPIZI_ORGANIZATION_ID = int(os.getenv("ZAPIZI_ORGANIZATION_ID", "3852"))

VENDPROMAX_BASE_URL = "https://api.vendpromax.com.br/api"
PAYXYZ_BASE_URL = "https://api2.payxyz.app.br/v2"
ZAPIZI_BASE_URL = "https://portal.zapizi.com.br/v1"

GREENAPI_BASE_URL = os.getenv(
    "GREENAPI_BASE_URL",
    "https://7107.api.greenapi.com/waInstance7107645608",
)
GREENAPI_SEND_PATH = os.getenv("GREENAPI_SEND_PATH", "")
GREENAPI_CHAT_ID = os.getenv("GREENAPI_CHAT_ID", "")

REQUEST_TIMEOUT_SECONDS = int(os.getenv("REQUEST_TIMEOUT_SECONDS", "30"))
