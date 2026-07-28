from fastapi import FastAPI

from app.api import api_router
from app.core import configure_logging, get_settings
from app.core.errors import install_exception_handlers


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging(settings)
    settings.validate_runtime()

    app = FastAPI(title=settings.service_name, version=settings.app_version)
    install_exception_handlers(app)
    app.include_router(api_router)
    return app


app = create_app()
