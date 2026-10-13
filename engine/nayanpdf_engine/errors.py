"""Engine error types with stable codes the UI can act on."""


class EngineError(Exception):
    """An expected, user-facing failure.

    ``code`` is a stable identifier (the UI keys behaviour off it, e.g. a
    PASSWORD_REQUIRED error triggers a password prompt). ``message`` must be
    safe to show to the user and must never contain document text or
    passwords.
    """

    def __init__(self, code: str, message: str, details: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}

    def to_dict(self) -> dict:
        return {"code": self.code, "message": self.message, "details": self.details}


# Stable error codes
PASSWORD_REQUIRED = "PASSWORD_REQUIRED"
PASSWORD_INVALID = "PASSWORD_INVALID"
PERMISSION_RESTRICTED = "PERMISSION_RESTRICTED"
INVALID_PDF = "INVALID_PDF"
UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
FILE_NOT_FOUND = "FILE_NOT_FOUND"
OUTPUT_EXISTS = "OUTPUT_EXISTS"
NO_SPACE = "NO_SPACE"
ACCESS_DENIED = "ACCESS_DENIED"
TOOL_MISSING = "TOOL_MISSING"
VALIDATION_FAILED = "VALIDATION_FAILED"
INVALID_ARGUMENT = "INVALID_ARGUMENT"
OPERATION_FAILED = "OPERATION_FAILED"
NOT_SUPPORTED = "NOT_SUPPORTED"
CANCELLED = "CANCELLED"


def invalid(message: str, **details) -> EngineError:
    return EngineError(INVALID_ARGUMENT, message, details)


def failed(message: str, **details) -> EngineError:
    return EngineError(OPERATION_FAILED, message, details)
