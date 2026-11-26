package cc.seebrook.finance

import cc.seebrook.platform.payments.PaymentMethod

/**
 * Why a payment failed, in words for the owner (ED-64). The provider's code (Stripe's decline or
 * bank return code) stays on the payment for operators; owners see one of these sentences, in the
 * app and in the failure email. Reasons that hint at fraud or a lost or stolen card read as a plain
 * decline, as card issuers ask.
 */
object PaymentFailures {
    fun describe(code: String?, method: PaymentMethod): String = when (method) {
        PaymentMethod.CARD -> card(code)
        PaymentMethod.ACH -> bank(code)
    }

    private fun card(code: String?): String = when (code) {
        "insufficient_funds" -> "Your card was declined for insufficient funds."
        "expired_card" -> "Your card has expired. Use another card."
        "incorrect_cvc", "invalid_cvc" -> "The card's security code was incorrect."
        "incorrect_number", "invalid_number" -> "The card number was incorrect."
        "authentication_required", "payment_intent_authentication_failure" ->
            "Your card's bank asked to confirm this payment, and it wasn't confirmed. Try again in the app."
        "processing_error" -> "The card couldn't be processed. Please try again."
        "payment_method_refused" -> "This card can no longer be used. Choose another payment method."
        else -> "Your card was declined. Try another card or contact your card's bank."
    }

    private fun bank(code: String?): String = when (code) {
        "insufficient_funds" -> "Your bank returned the payment for insufficient funds."
        "account_closed" -> "The bank account is closed. Use another account."
        "no_account", "invalid_account_number", "account_number_invalid" -> "The bank couldn't find this account."
        "debit_not_authorized" -> "Your bank reported this payment as not authorized."
        "bank_account_restricted" -> "This bank account can't be debited. Use another account."
        "payment_method_refused" -> "This bank account can no longer be used. Choose another payment method."
        else -> "Your bank declined the payment."
    }
}
