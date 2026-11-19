package cc.seebrook.app

import cc.seebrook.platform.config.AppConfig
import cc.seebrook.platform.config.PaymentsConfig
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.SimulatedPaymentProvider
import cc.seebrook.platform.payments.StripePaymentProvider

/** Composition root for payments (ED-43): the only place that names concrete providers. */
object PaymentSetup {
    fun providers(config: AppConfig): PaymentProviders = when (val p = config.payments) {
        is PaymentsConfig.Stripe -> PaymentProviders(listOf(StripePaymentProvider(p.secretKey, p.publishableKey, p.webhookSecret, p.apiBase)))
        is PaymentsConfig.Simulated -> PaymentProviders(listOf(SimulatedPaymentProvider(p.webhookSecret)))
        PaymentsConfig.Disabled -> PaymentProviders.NONE
    }
}
