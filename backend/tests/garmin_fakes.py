from __future__ import annotations

import json
from pathlib import Path


class FakeTokenClient:
    def __init__(self, payload: dict[str, str] | None = None) -> None:
        self.payload = payload or {
            "di_token": "access-token",
            "di_refresh_token": "refresh-token",
            "di_client_id": "client-id",
        }
        self.dump_calls: list[str] = []
        self.load_calls: list[str] = []
        self.fail_dump = False
        self.fail_load: Exception | None = None

    def dumps(self) -> str:
        return json.dumps(self.payload)

    def dump(self, path: str) -> None:
        self.dump_calls.append(path)
        if self.fail_dump:
            raise RuntimeError("dump failed")
        Path(path).write_text(self.dumps(), encoding="utf-8")

    def load(self, path: str) -> None:
        self.load_calls.append(path)
        if self.fail_load is not None:
            raise self.fail_load
        raw = Path(path).read_text(encoding="utf-8")
        self.payload = json.loads(raw)


class FakeGarminClient:
    def __init__(
        self,
        *,
        username: str | None,
        password: str | None,
        token_client: FakeTokenClient | None = None,
        login_result: tuple[str | None, str | None] = (None, None),
        login_error: Exception | None = None,
        full_name: str | None = "Test User",
    ) -> None:
        self.username = username
        self.password = password
        self.client = token_client or FakeTokenClient()
        self.login_result = login_result
        self.login_error = login_error
        self.full_name = full_name
        self.login_calls: list[str | None] = []

    def login(self, tokenstore: str | None = None) -> tuple[str | None, str | None]:
        self.login_calls.append(tokenstore)
        if self.login_error is not None:
            raise self.login_error
        if tokenstore is None and (not self.username or not self.password):
            raise RuntimeError("credentials required")
        if tokenstore is not None:
            self.client.load(tokenstore)
        return self.login_result

    def get_full_name(self) -> str | None:
        return self.full_name


class FakeGarminFactory:
    def __init__(self, client: FakeGarminClient | None = None) -> None:
        self.client = client
        self.created_with: list[tuple[str | None, str | None]] = []

    def create(self, username: str | None, password: str | None) -> FakeGarminClient:
        self.created_with.append((username, password))
        if self.client is not None:
            self.client.username = username
            self.client.password = password
            return self.client
        return FakeGarminClient(username=username, password=password)


class FakeMetricsClient:
    """In-memory Garmin client used only for metric-adapter tests."""

    def __init__(
        self,
        *,
        sleep: object | None = None,
        hrv: object | None = None,
        hrv_by_date: dict[str, object] | None = None,
        body_battery: object | None = None,
        rhr: object | None = None,
        stress: object | None = None,
        stats: object | None = None,
        training_readiness: object | None = None,
        device_last_used: object | None = None,
        activities: object | None = None,
        errors: dict[str, Exception] | None = None,
    ) -> None:
        self.sleep = sleep
        self.hrv = hrv
        self.hrv_by_date = hrv_by_date or {}
        self.body_battery = body_battery
        self.rhr = rhr
        self.stress = stress
        self.stats = stats
        self.training_readiness = training_readiness
        self.device_last_used = device_last_used
        self.activities = activities
        self.errors = errors or {}
        self.calls: list[tuple[str, tuple[object, ...]]] = []

    def get_sleep_data(self, cdate: str) -> object:
        return self._invoke("get_sleep_data", self.sleep, cdate)

    def get_hrv_data(self, cdate: str) -> object:
        if cdate in self.hrv_by_date:
            return self._invoke("get_hrv_data", self.hrv_by_date[cdate], cdate)
        return self._invoke("get_hrv_data", self.hrv, cdate)

    def get_body_battery(
        self, startdate: str, enddate: str | None = None
    ) -> object:
        return self._invoke("get_body_battery", self.body_battery, startdate, enddate)

    def get_rhr_day(self, cdate: str) -> object:
        return self._invoke("get_rhr_day", self.rhr, cdate)

    def get_stress_data(self, cdate: str) -> object:
        return self._invoke("get_stress_data", self.stress, cdate)

    def get_stats(self, cdate: str) -> object:
        return self._invoke("get_stats", self.stats, cdate)

    def get_training_readiness(self, cdate: str) -> object:
        return self._invoke("get_training_readiness", self.training_readiness, cdate)

    def get_device_last_used(self) -> object:
        return self._invoke("get_device_last_used", self.device_last_used)

    def get_activities(self, start: int, limit: int) -> object:
        return self._invoke("get_activities", self.activities, start, limit)

    def _invoke(self, name: str, payload: object, *args: object) -> object:
        self.calls.append((name, args))
        if name in self.errors:
            raise self.errors[name]
        return payload
