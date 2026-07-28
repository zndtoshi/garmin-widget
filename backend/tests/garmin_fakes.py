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
