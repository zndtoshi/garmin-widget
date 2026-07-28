import os

from fastapi.testclient import TestClient

from app.main import create_app


def test_health_status_code(client) -> None:
    response = client.get("/health")
    assert response.status_code == 200


def test_health_response_contract(client) -> None:
    response = client.get("/health")
    assert response.json() == {
        "status": "ok",
        "service": "garmin-widget-backend",
        "version": "0.1.0",
    }


def test_health_content_type_json(client) -> None:
    response = client.get("/health")
    assert response.headers["content-type"].startswith("application/json")


def test_health_uses_configured_metadata() -> None:
    os.environ["GARMIN_WIDGET_SERVICE_NAME"] = "custom-widget-service"
    os.environ["GARMIN_WIDGET_APP_VERSION"] = "9.9.9"
    client = TestClient(create_app())
    response = client.get("/health")

    assert response.json()["service"] == "custom-widget-service"
    assert response.json()["version"] == "9.9.9"
