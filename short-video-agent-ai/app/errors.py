class ServiceError(Exception):
    def __init__(self, message: str, status: int = 502, code: str = "UPSTREAM_ERROR"):
        super().__init__(message)
        self.message, self.status, self.code = message, status, code


def model_http_error(status, body="", retries=0):
    # Read only the provider's machine code; never expose upstream bodies or credentials.
    import json
    code = ""
    try:
        error = json.loads(body).get("error", {})
        if isinstance(error, dict):
            code = error.get("code", "")
    except (ValueError, TypeError, AttributeError):
        pass
    prefix = f"已自动重试 {retries} 次，仍未成功。" if retries else ""
    if status == 429:
        if code == "free_campaign_global_limit":
            return ServiceError(prefix + "当前免费模型达到供应商全局限流，请稍后重试。", 503, "MODEL_FREE_GLOBAL_LIMIT")
        return ServiceError(prefix + "模型服务请求过于频繁或已达到额度限制，请稍后重试。", 503, "MODEL_RATE_LIMITED")
    if status in (401, 403):
        return ServiceError("模型服务认证失败，请检查密钥与访问权限。", 502, "MODEL_AUTH_ERROR")
    return ServiceError(prefix + f"模型服务暂时不可用（HTTP {status}），请稍后重试。", 502, "MODEL_HTTP_ERROR")


def retry_delay(attempt, response=None):
    from datetime import datetime, timezone
    from email.utils import parsedate_to_datetime
    delay = min(2 ** (attempt + 1), 30)
    value = response.headers.get("retry-after") if response is not None else None
    if value:
        try:
            seconds = float(value)
        except ValueError:
            try:
                seconds = (parsedate_to_datetime(value) - datetime.now(timezone.utc)).total_seconds()
            except (ValueError, TypeError, OverflowError):
                seconds = 0
        delay = max(delay, min(max(seconds, 0), 60))
    return delay

