from fastapi import APIRouter

from app.models.health import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
def get_health() -> HealthResponse:
    return HealthResponse(status="ok", service="garmin-widget-backend", version="0.1.0")
