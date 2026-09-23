package com.tony.appbooster.domain.model.common

/**
 * Signals that privileged shell access is unavailable, so unattempted packages can be retried.
 *
 * @param message Connection or permission failure presented to the user.
 */
class ShellConnectionException(message: String) : IllegalStateException(message)
