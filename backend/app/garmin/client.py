from __future__ import annotations

from typing import Protocol

from garminconnect import Garmin


class GarminFactory:
    def create(self, username: str | None, password: str | None) -> Garmin:
        return Garmin(
            email=username,
            password=password,
            return_on_mfa=True,
        )


class GarminClientFactoryProtocol(Protocol):
    def create(self, username: str | None, password: str | None) -> object:
        ...
