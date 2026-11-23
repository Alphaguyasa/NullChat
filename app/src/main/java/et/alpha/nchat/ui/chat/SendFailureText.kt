package et.alpha.nchat.ui.chat

import androidx.annotation.StringRes
import et.alpha.nchat.R
import et.alpha.nchat.mesh.SendFailure

/**
 * The snackbar text for a send that did not go out.
 *
 * An exhaustive `when`, so a new [SendFailure] does not compile until it has
 * words; `SendFailureTextTest` checks no two codes share a sentence by accident.
 */
@StringRes
internal fun sendFailureText(failure: SendFailure): Int = when (failure) {
    SendFailure.EMPTY -> R.string.send_failure_empty
    SendFailure.TEXT_TOO_LONG -> R.string.send_failure_text_too_long
    SendFailure.IMAGE_TOO_LARGE -> R.string.send_failure_image_too_large
    SendFailure.IMAGE_NOT_SAVED -> R.string.send_failure_image_not_saved
    SendFailure.NOT_PREPARED -> R.string.send_failure_not_prepared
    SendFailure.NO_KEY -> R.string.send_failure_no_key
    SendFailure.NOBODY_IN_RANGE -> R.string.send_failure_nobody_in_range
    SendFailure.ALREADY_RESENDING -> R.string.send_failure_already_resending
    SendFailure.MESSAGE_GONE -> R.string.send_failure_message_gone
    SendFailure.NOT_FAILED -> R.string.send_failure_not_failed
    SendFailure.IMAGE_GONE -> R.string.send_failure_image_gone
}
