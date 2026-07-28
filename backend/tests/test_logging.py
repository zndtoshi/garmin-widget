import logging

from app.core.config import Settings
from app.core.logging import REDACTED, SecretRedactionFilter, UtcFormatter, configure_logging


class UnrelatedFilter(logging.Filter):
    pass


def test_logging_filter_redacts_configured_secret() -> None:
    record = logging.LogRecord(
        name="test",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="token super-secret leaked",
        args=(),
        exc_info=None,
    )
    redaction_filter = SecretRedactionFilter(["super-secret"])

    assert redaction_filter.filter(record) is True
    assert REDACTED in record.msg
    assert "super-secret" not in record.msg


def test_utc_formatter_uses_utc_timestamp() -> None:
    formatter = UtcFormatter("%(asctime)s", datefmt="%Y-%m-%dT%H:%M:%SZ")
    record = logging.LogRecord(
        name="test",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="hello",
        args=(),
        exc_info=None,
    )
    record.created = 0
    record.msecs = 0

    assert formatter.format(record) == "1970-01-01T00:00:00Z"


def test_configure_logging_preserves_unrelated_filters() -> None:
    root_logger = logging.getLogger()
    original_handlers = root_logger.handlers[:]
    original_level = root_logger.level
    handler = logging.StreamHandler()
    unrelated_filter = UnrelatedFilter()
    handler.addFilter(unrelated_filter)
    root_logger.handlers = [handler]

    try:
        configure_logging(Settings(widget_bearer_token="secret-token"))
        assert unrelated_filter in handler.filters
        assert any(isinstance(filter_, SecretRedactionFilter) for filter_ in handler.filters)
    finally:
        root_logger.handlers = original_handlers
        root_logger.setLevel(original_level)


def test_configure_logging_is_idempotent() -> None:
    root_logger = logging.getLogger()
    original_handlers = root_logger.handlers[:]
    original_level = root_logger.level
    handler = logging.StreamHandler()
    root_logger.handlers = [handler]

    try:
        configure_logging(Settings(widget_bearer_token="first-token"))
        configure_logging(Settings(widget_bearer_token="second-token"))
        redaction_filters = [
            filter_ for filter_ in handler.filters if isinstance(filter_, SecretRedactionFilter)
        ]
        assert len(redaction_filters) == 1
    finally:
        root_logger.handlers = original_handlers
        root_logger.setLevel(original_level)


def test_configure_logging_uses_latest_secrets() -> None:
    root_logger = logging.getLogger()
    original_handlers = root_logger.handlers[:]
    original_level = root_logger.level
    handler = logging.StreamHandler()
    root_logger.handlers = [handler]

    try:
        configure_logging(Settings(widget_bearer_token="old-token"))
        configure_logging(Settings(widget_bearer_token="new-token"))
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname=__file__,
            lineno=1,
            msg="token new-token leaked",
            args=(),
            exc_info=None,
        )
        for filter_ in handler.filters:
            filter_.filter(record)
        assert REDACTED in record.msg
        assert "new-token" not in record.msg
    finally:
        root_logger.handlers = original_handlers
        root_logger.setLevel(original_level)
