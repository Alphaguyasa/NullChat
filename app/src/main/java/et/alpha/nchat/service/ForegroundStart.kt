package et.alpha.nchat.service

/**
 * Whether [e], thrown by `Service.startForeground`, means the system refused to
 * let this service run in the foreground, rather than a bug worth crashing on.
 *
 * Two refusals are expected and must end in `stopSelf()`, not a crash:
 * - [SecurityException]: on API 34+ a `connectedDevice` service needs one of the
 *   Bluetooth runtime permissions at the moment it goes foreground. Revoking them
 *   in Settings kills the process, START_STICKY recreates the service, and it
 *   would throw here on every restart.
 * - [IllegalStateException]: `ForegroundServiceStartNotAllowedException` (API 31+)
 *   extends it, thrown when a start from the background is not allowed.
 */
internal fun isForegroundStartRefusal(e: Throwable): Boolean =
    e is SecurityException || e is IllegalStateException
