class ServiceError(Exception):
    def __init__(self, message: str, status: int = 502, code: str = "UPSTREAM_ERROR"):
        super().__init__(message)
        self.message, self.status, self.code = message, status, code

