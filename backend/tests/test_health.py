from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_status_code() -> None:
    response = client.get("/health")
    assert response.status_code == 200


def test_health_response_contract() -> None:
    response = client.get("/health")
    assert response.json() == {
        "status": "ok",
        "service": "garmin-widget-backend",
        "version": "0.1.0",
    }


def test_health_content_type_json() -> None:
    response = client.get("/health")
    assert response.headers["content-type"].startswith("application/json")
