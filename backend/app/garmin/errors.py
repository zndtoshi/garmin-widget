class GarminSessionError(Exception):
    """Base class for Garmin session lifecycle failures."""


class MissingGarminCredentialsError(GarminSessionError):
    """Credential login was required but credentials were unavailable."""


class GarminCorruptSessionError(GarminSessionError):
    """Saved Garmin session material is unreadable or malformed."""


class GarminAuthenticationFailedError(GarminSessionError):
    """Garmin rejected the configured credentials or session."""


class GarminMfaRequiredError(GarminSessionError):
    """Garmin requires interactive MFA or another unsupported challenge."""


class GarminRateLimitError(GarminSessionError):
    """Garmin temporarily rate-limited authentication or session refresh."""


class GarminNetworkError(GarminSessionError):
    """Network or timeout error while reaching Garmin."""


class GarminUpstreamError(GarminSessionError):
    """Unexpected upstream Garmin failure."""
