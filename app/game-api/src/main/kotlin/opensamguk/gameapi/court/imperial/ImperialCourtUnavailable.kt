package opensamguk.gameapi.court.imperial

/** Reader source validation failed; propagate through transaction proxies before HTTP translation. */
class ImperialCourtUnavailable(cause: RuntimeException) : RuntimeException("Imperial court source unavailable", cause)
