from __future__ import annotations

from pydantic import ValidationError

from app.core.config import get_settings
from app.garmin.client import GarminFactory
from app.garmin.errors import GarminSessionError
from app.garmin.session import GarminSessionManager
from app.garmin.store import FilesystemSessionStore


def build_session_manager() -> GarminSessionManager:
    settings = get_settings()
    settings.validate_runtime()
    return GarminSessionManager(
        settings=settings,
        session_store=FilesystemSessionStore(settings.data_dir),
        client_factory=GarminFactory(),
    )


def main() -> int:
    try:
        garmin = build_session_manager().initialize_session()
        garmin.get_full_name()
        print("Garmin authentication check succeeded.")
        return 0
    except GarminSessionError as exc:
        print(f"Garmin authentication check failed: {exc}")
        return 1
    except (ValueError, ValidationError):
        print("Garmin authentication check failed: invalid configuration.")
        return 1
    except Exception:
        print("Garmin authentication check failed: unexpected error.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
