# syntax=docker/dockerfile:1
# Base image pin: bump the python:3.12.x-slim tag intentionally when upgrading.
# uv pin: bump the astral-sh/uv tag below when upgrading the package manager.
FROM python:3.12.11-slim-bookworm

LABEL org.opencontainers.image.title="garmin-widget-backend" \
      org.opencontainers.image.description="Private FastAPI backend for the garmin-widget Android client"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8000

WORKDIR /app

COPY --from=ghcr.io/astral-sh/uv:0.11.32 /uv /uvx /bin/

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY app ./app

RUN useradd --create-home --uid 10001 appuser \
    && chown -R appuser:appuser /app

USER appuser

EXPOSE 8000

# Uses PORT so local default and Render-injected ports stay consistent.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import os, urllib.request; port=os.environ.get('PORT','8000'); urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=3)"

# Single Uvicorn worker: refresh locking is process-local.
# Use the image venv directly so startup never resolves/syncs dependencies.
CMD ["/app/.venv/bin/python", "-m", "app.server"]
