from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, status

from app.api.auth import require_widget_bearer
from app.core.errors import AppError
from app.models.widget import WidgetResponse
from app.persistence.errors import (
    CorruptCacheError,
    NoCachedWidgetError,
    RefreshFailedError,
)
from app.services.factory import get_widget_refresh_service
from app.services.refresh import WidgetRefreshService

router = APIRouter(
    prefix="/api/v1/widget",
    tags=["widget"],
    dependencies=[Depends(require_widget_bearer)],
)


@router.get(
    "/latest",
    response_model=WidgetResponse,
    response_model_by_alias=True,
    summary="Get the latest successful widget payload",
    description=(
        "Returns the last successful cached widget payload. "
        "Never contacts Garmin Connect."
    ),
)
def get_latest(
    service: Annotated[WidgetRefreshService, Depends(get_widget_refresh_service)],
) -> WidgetResponse:
    try:
        return service.get_latest()
    except NoCachedWidgetError as exc:
        raise AppError(
            "No widget data is available.",
            status_code=status.HTTP_404_NOT_FOUND,
        ) from exc
    except CorruptCacheError as exc:
        raise AppError(
            "Widget data is temporarily unavailable.",
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        ) from exc


@router.post(
    "/refresh",
    response_model=WidgetResponse,
    response_model_by_alias=True,
    summary="Refresh widget metrics",
    description=(
        "Performs a live Garmin refresh when cooldown allows; otherwise returns "
        "cached cooldown or stale fallback data."
    ),
)
def refresh_widget(
    service: Annotated[WidgetRefreshService, Depends(get_widget_refresh_service)],
) -> WidgetResponse:
    try:
        return service.refresh()
    except RefreshFailedError as exc:
        raise AppError(
            "Widget refresh is temporarily unavailable.",
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        ) from exc
    except CorruptCacheError as exc:
        raise AppError(
            "Widget data is temporarily unavailable.",
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        ) from exc
