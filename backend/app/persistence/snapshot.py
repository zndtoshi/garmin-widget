from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Protocol

from pydantic import ValidationError

from app.models.widget import WIDGET_SCHEMA_VERSION
from app.persistence.atomic import write_text_atomic
from app.persistence.errors import (
    CorruptCacheError,
    NoCachedWidgetError,
    PersistenceWriteError,
)
from app.persistence.models import SNAPSHOT_FORMAT_VERSION, WidgetSnapshot

logger = logging.getLogger(__name__)


class WidgetSnapshotRepository(Protocol):
    def load(self) -> WidgetSnapshot: ...

    def save(self, snapshot: WidgetSnapshot) -> None: ...

    def path(self) -> Path: ...

    def data_dir(self) -> Path: ...


class FilesystemWidgetSnapshotRepository:
    """Persist the latest successful widget snapshot as one atomic JSON file."""

    def __init__(self, data_dir: Path) -> None:
        self._data_dir = data_dir
        self._directory = data_dir / "widget"
        self._path = self._directory / "latest_snapshot.json"

    def data_dir(self) -> Path:
        return self._data_dir

    def path(self) -> Path:
        return self._path

    def load(self) -> WidgetSnapshot:
        if not self._path.is_file():
            raise NoCachedWidgetError("No successful widget snapshot is available.")

        try:
            raw = self._path.read_text(encoding="utf-8")
        except OSError as exc:
            logger.warning("Failed to read widget snapshot file.")
            raise CorruptCacheError(
                "Widget snapshot could not be read due to an I/O error."
            ) from exc

        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            logger.warning("Widget snapshot JSON is malformed or truncated.")
            raise CorruptCacheError(
                "Widget snapshot is malformed or truncated."
            ) from exc

        if not isinstance(data, dict):
            logger.warning("Widget snapshot root value has an unexpected type.")
            raise CorruptCacheError("Widget snapshot is malformed or truncated.")

        format_version = data.get("persistenceFormatVersion")
        if format_version != SNAPSHOT_FORMAT_VERSION:
            logger.warning("Widget snapshot persistence format is unsupported.")
            raise CorruptCacheError(
                "Widget snapshot persistence format is unsupported."
            )

        payload = data.get("payload")
        if isinstance(payload, dict) and payload.get("schemaVersion") != WIDGET_SCHEMA_VERSION:
            logger.warning("Widget snapshot payload schema version is unsupported.")
            raise CorruptCacheError(
                "Widget snapshot payload schema version is unsupported."
            )

        try:
            return WidgetSnapshot.model_validate(data)
        except ValidationError as exc:
            logger.warning("Widget snapshot failed schema validation.")
            raise CorruptCacheError(
                "Widget snapshot is malformed or truncated."
            ) from exc

    def save(self, snapshot: WidgetSnapshot) -> None:
        try:
            validated = WidgetSnapshot.model_validate(
                snapshot.model_dump(by_alias=True)
            )
        except ValidationError as exc:
            logger.warning("Refusing to persist an invalid widget snapshot.")
            raise PersistenceWriteError(
                "Refusing to persist an invalid widget snapshot."
            ) from exc

        if validated.persistence_format_version != SNAPSHOT_FORMAT_VERSION:
            raise PersistenceWriteError(
                "Refusing to persist an unsupported snapshot format version."
            )
        if validated.payload.schema_version != WIDGET_SCHEMA_VERSION:
            raise PersistenceWriteError(
                "Refusing to persist an unsupported widget schema version."
            )
        try:
            content = json.dumps(
                validated.model_dump(by_alias=True, mode="json"),
                separators=(",", ":"),
                ensure_ascii=False,
            )
            write_text_atomic(self._path, content, temp_prefix="latest_snapshot")
        except PersistenceWriteError:
            raise
        except Exception as exc:
            logger.warning("Failed to persist widget snapshot.")
            raise PersistenceWriteError(
                "Failed to persist the widget snapshot."
            ) from exc
