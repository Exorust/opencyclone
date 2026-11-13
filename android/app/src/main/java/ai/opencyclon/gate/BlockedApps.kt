package ai.opencyclon.gate

object BlockedApps {
    /** Fixed entries other than this app; [all] adds this app by its real application id. */
    val FIXED: Set<String> = java.util.Collections.unmodifiableSet(setOf(
        "moe.shizuku.privileged.api", "com.android.settings",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
        "com.duosecurity.duomobile", "org.fedorahosted.freeotp", "org.fedorahosted.freeotpplus",
        "com.beemdevelopment.aegis", "com.yubico.yubioath", "com.okta.android.auth",
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.onepassword.android",
        "com.lastpass.lpandroid", "com.dashlane", "com.keepersecurity.keeper",
        "keepass2android.keepass2android", "com.kunzisoft.keepass.free", "com.enpass.passwordmanager",
        "com.protonpass.android", "com.nordpass.android.app.password.manager",
        "com.chase.sig.android", "com.infonow.bofa", "com.wf.wellsfargomobile",
        "com.citi.citimobile", "com.konylabs.capitalone", "com.usbank.mobilebanking",
        "com.pnc.ecommerce.mobile", "com.tdbank", "com.ally.MobileBanking",
        "com.bbt.myfi", "com.discoverfinancial.mobile", "com.americanexpress.android.acctsvcs.us",
        "com.revolut.revolut", "de.number26.android", "com.monzo.android", "com.starlingbank.android",
        "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash",
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user",
        "com.samsung.android.spay", "net.one97.paytm", "com.phonepe.app", "com.eg.android.AlipayGphone",
        "com.coinbase.android", "com.coinbase.wallet", "io.metamask", "com.wallet.crypto.trustapp",
        "piuk.blockchain.android", "com.binance.dev"
    ))

    /** [ownPackage] is `context.packageName`, so renaming the application id cannot unblock this app. */
    fun all(ownerAdded: Set<String>, ownPackage: String): Set<String> = FIXED + ownPackage + ownerAdded
}
