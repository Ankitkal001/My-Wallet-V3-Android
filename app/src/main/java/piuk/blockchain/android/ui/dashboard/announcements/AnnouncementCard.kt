package piuk.blockchain.android.ui.dashboard.announcements

import android.support.annotation.ColorRes
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import piuk.blockchain.android.R

data class AnnouncementCard(
    val dismissRule: DismissRule,
    val dismissEntry: DismissRecorder.DismissEntry,
    @StringRes val titleText: Int = 0,
    @StringRes val bodyText: Int = 0,
    @StringRes val ctaText: Int = 0,
    @StringRes val dismissText: Int = 0,
    @DrawableRes val iconImage: Int = 0,
    @ColorRes val buttonColor: Int = R.color.default_announce_button,
    private val ctaFunction: () -> Unit = { },
    private val dismissFunction: () -> Unit = { }
) {
    fun ctaClicked() {
        dismissEntry.done()
        ctaFunction.invoke()
    }

    fun dismissClicked() {
        dismissEntry.dismiss(dismissRule)
        dismissFunction.invoke()
    }

    val dismissKey: String
        get() = dismissEntry.prefsKey
}
