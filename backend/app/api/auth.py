from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import Settings, get_settings
from app.core.errors import AppError

_bearer_scheme = HTTPBearer(
    auto_error=False,
    scheme_name="WidgetBearer",
    description="Private widget bearer token.",
)

_UNAUTHORIZED_DETAIL = "Unauthorized"
_AUTH_NOT_CONFIGURED_DETAIL = "Widget authentication is not configured."


def require_widget_bearer(
    credentials: Annotated[
        HTTPAuthorizationCredentials | None, Depends(_bearer_scheme)
    ],
    settings: Annotated[Settings, Depends(get_settings)],
) -> None:
    """Require a valid private widget bearer token for `/api/v1/widget/*`."""
    expected = _configured_widget_token(settings)
    if expected is None:
        raise AppError(
            _AUTH_NOT_CONFIGURED_DETAIL,
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        )

    if (
        credentials is None
        or credentials.scheme.lower() != "bearer"
        or not credentials.credentials
        or not tokens_match(credentials.credentials, expected)
    ):
        raise unauthorized_exception()


def unauthorized_exception() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=_UNAUTHORIZED_DETAIL,
        headers={"WWW-Authenticate": "Bearer"},
    )


def tokens_match(presented: str, expected: str) -> bool:
    """Timing-safe token comparison used by widget authentication."""
    presented_bytes = presented.encode("utf-8")
    expected_bytes = expected.encode("utf-8")
    if len(presented_bytes) != len(expected_bytes):
        secrets.compare_digest(expected_bytes, expected_bytes)
        return False
    return secrets.compare_digest(presented_bytes, expected_bytes)


def _configured_widget_token(settings: Settings) -> str | None:
    token = settings.widget_bearer_token
    if token is None:
        return None
    value = token.get_secret_value().strip()
    if not value:
        return None
    return value
