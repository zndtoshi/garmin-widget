from __future__ import annotations

import logging
from http import HTTPStatus

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

logger = logging.getLogger(__name__)


class AppError(Exception):
    def __init__(self, client_message: str, status_code: int = HTTPStatus.BAD_REQUEST) -> None:
        super().__init__(client_message)
        self.client_message = client_message
        self.status_code = int(status_code)


async def app_error_handler(_: Request, exc: AppError) -> JSONResponse:
    logger.warning("application error: %s", exc.client_message)
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.client_message})


async def validation_exception_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    logger.info("request validation failed: %s", exc.__class__.__name__)
    return JSONResponse(
        status_code=HTTPStatus.UNPROCESSABLE_ENTITY,
        content={"detail": "Invalid request."},
    )


async def unhandled_exception_handler(_: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled server error: %s", exc.__class__.__name__)
    return JSONResponse(
        status_code=HTTPStatus.INTERNAL_SERVER_ERROR,
        content={"detail": "Internal server error."},
    )


def install_exception_handlers(app: FastAPI) -> None:
    app.add_exception_handler(AppError, app_error_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)
