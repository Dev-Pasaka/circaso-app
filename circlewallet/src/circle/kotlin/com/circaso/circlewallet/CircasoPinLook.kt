package com.circaso.circlewallet

import android.content.Context
import android.graphics.Typeface
import android.widget.ImageView
import circle.programmablewallet.sdk.presentation.IImageViewSetter
import circle.programmablewallet.sdk.presentation.IconTextConfig
import circle.programmablewallet.sdk.presentation.LayoutProvider
import circle.programmablewallet.sdk.presentation.Resource
import circle.programmablewallet.sdk.presentation.TextConfig
import circle.programmablewallet.sdk.presentation.ViewSetterProvider
import com.circaso.feature.wallet.domain.SignSubject

/**
 * Circaso's look inside Circle's PIN, security-question and biometrics screens: the Circaso mark on the PIN intro,
 * Circaso line icons for every image slot (an empty slot is a blank space), and the
 * headlines in Circaso's ink and type. Colours come from the `circlepw_*` resources, which follow dark mode.
 */
internal class CircasoPinImages(private val context: Context) : ViewSetterProvider() {
    override fun getImageSetter(icon: Resource.Icon): IImageViewSetter {
        val res = when (icon) {
            Resource.Icon.securityIntroMain -> R.drawable.circaso_pw_mark
            Resource.Icon.securityConfirmMain -> R.drawable.circaso_pw_shield
            Resource.Icon.biometricsAllowMain -> R.drawable.circaso_pw_fingerprint
            Resource.Icon.alertWindowIcon -> R.drawable.circaso_pw_lock
            Resource.Icon.selectCheckMark, Resource.Icon.swipeBtConfirmed -> R.drawable.circaso_pw_check
            Resource.Icon.dropdownArrow, Resource.Icon.showMoreDetailArrow -> R.drawable.circaso_pw_chevron_down
            Resource.Icon.showLessDetailArrow -> R.drawable.circaso_pw_chevron_up
            Resource.Icon.errorInfo -> R.drawable.circaso_pw_error
            Resource.Icon.showPin -> R.drawable.circaso_pw_eye
            Resource.Icon.hidePin -> R.drawable.circaso_pw_eye_off
            Resource.Icon.networkFeeTipIcon -> R.drawable.circaso_pw_info
            Resource.Icon.transactionTokenIcon -> R.drawable.circaso_pw_token
            Resource.Icon.requestIcon -> R.drawable.circaso_pw_request
            Resource.Icon.swipeItemIcon -> R.drawable.circaso_pw_chevron_down
        }
        return image(res)
    }
}

/**
 * Circle's headlines, naming the wallet the challenge is for. Each wallet (personal, every group wallet) is its own
 * Circle user with its own PIN, so a group wallet's screens must never read like the personal one's (GW2, GW3, GW11):
 * "Set a PIN for Kilimani Chama", "Approve from Kilimani Chama". [subject] is set before every call; calls never overlap.
 */
internal class CircasoPinTexts(private val context: Context) : LayoutProvider() {
    companion object {
        @Volatile var subject: SignSubject = SignSubject.Personal
    }

    private val ink get() = context.getColor(R.color.circlepw_text_main)
    private val soft get() = context.getColor(R.color.circlepw_text_auxiliary)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)

    /** A headline in two parts, the second carrying the weight: "Choose your **wallet PIN**". */
    private fun headline(lead: String, strong: String): Array<TextConfig?> = arrayOf(TextConfig(lead, ink, bold), TextConfig(strong, ink, bold))

    override fun getTextConfigs(key: Resource.TextsKey): Array<TextConfig?>? {
        // Named whenever the app names it: a group wallet, and Personal when the person has others (GW11b).
        val named = subject.name
        return when (key) {
            Resource.TextsKey.newPinCodeHeadline -> if (named != null) headline("Set a PIN for ", named) else headline("Choose your ", "wallet PIN")
            Resource.TextsKey.enterPinCodeHeadline -> if (named != null) headline("Approve from ", named) else headline("Enter your ", "wallet PIN")
            Resource.TextsKey.recoverPinCodeHeadline -> headline("Choose a ", "new PIN")
            Resource.TextsKey.securityIntroHeadline -> if (named != null) headline("Protect ", named) else headline("Protect your ", "wallet")
            Resource.TextsKey.securityQuestionHeaders -> arrayOf(TextConfig("First question", soft, regular), TextConfig("Second question", soft, regular))
            Resource.TextsKey.securitySummaryQuestionHeaders -> arrayOf(TextConfig("First question", soft, regular), TextConfig("Second question", soft, regular))
            // Circle's own explainer link stays: Circaso has no page of its own for this.
            Resource.TextsKey.securityIntroLink -> super.getTextConfigs(key)
        }
    }

    override fun getIconTextConfigs(key: Resource.IconTextsKey): Array<IconTextConfig?>? = when (key) {
        Resource.IconTextsKey.securityConfirmationItems -> arrayOf(
            IconTextConfig(image(R.drawable.circaso_pw_lock), TextConfig("If I forget my PIN, my answers are the only way back into my wallet.", ink, regular)),
            IconTextConfig(image(R.drawable.circaso_pw_shield), TextConfig("Nobody at Circaso can see my answers or reset my PIN.", ink, regular)),
        )
    }

    override fun getDateFormat(): String = "dd/MM/yyyy"
}

/** Sets one of this module's drawables on Circle's image view. */
private fun image(res: Int): IImageViewSetter = object : IImageViewSetter {
    override fun apply(iv: ImageView?) { iv?.setImageResource(res) }
}
