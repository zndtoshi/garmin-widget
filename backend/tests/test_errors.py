from fastapi import APIRouter
from fastapi.testclient import TestClient

from app.main import create_app


def test_unhandled_errors_are_sanitized() -> None:
    app = create_app()
    router = APIRouter()

    @router.get("/boom")
    def boom() -> None:
        raise RuntimeError("secret should not reach client")

    app.include_router(router)

    client = TestClient(app, raise_server_exceptions=False)
    response = client.get("/boom")

    assert response.status_code == 500
    assert response.json() == {"detail": "Internal server error."}
    assert "secret" not in response.text
