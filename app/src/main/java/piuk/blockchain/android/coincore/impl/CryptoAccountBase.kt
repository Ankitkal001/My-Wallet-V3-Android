package piuk.blockchain.android.coincore.impl

import com.blockchain.swap.nabu.datamanagers.CustodialWalletManager
import com.blockchain.swap.nabu.datamanagers.SwapDirection
import com.blockchain.swap.nabu.datamanagers.repositories.swap.SwapTransactionItem
import com.blockchain.swap.nabu.models.interest.DisabledReason
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.ExchangeRates
import info.blockchain.balance.FiatValue
import info.blockchain.balance.Money
import info.blockchain.balance.total
import info.blockchain.wallet.multiaddress.TransactionSummary
import io.reactivex.Single
import piuk.blockchain.android.coincore.AccountGroup
import piuk.blockchain.android.coincore.ActivitySummaryItem
import piuk.blockchain.android.coincore.ActivitySummaryList
import piuk.blockchain.android.coincore.AssetAction
import piuk.blockchain.android.coincore.AvailableActions
import piuk.blockchain.android.coincore.BlockchainAccount
import piuk.blockchain.android.coincore.CryptoAccount
import piuk.blockchain.android.coincore.NonCustodialAccount
import piuk.blockchain.android.coincore.NonCustodialActivitySummaryItem
import piuk.blockchain.android.coincore.ReceiveAddress
import piuk.blockchain.android.coincore.SingleAccountList
import piuk.blockchain.android.coincore.SwapActivitySummaryItem
import piuk.blockchain.android.coincore.TxEngine
import piuk.blockchain.android.coincore.TxSourceState
import piuk.blockchain.androidcore.data.api.EnvironmentConfig
import piuk.blockchain.androidcore.data.exchangerate.ExchangeRateDataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import java.math.BigInteger

internal const val transactionFetchCount = 50
internal const val transactionFetchOffset = 0

abstract class CryptoAccountBase : CryptoAccount {

    protected abstract val exchangeRates: ExchangeRateDataManager

    final override var hasTransactions: Boolean = false
        private set

    final override fun fiatBalance(
        fiatCurrency: String,
        exchangeRates: ExchangeRates
    ): Single<Money> =
        accountBalance.map { it.toFiat(exchangeRates, fiatCurrency) }

    protected fun setHasTransactions(hasTransactions: Boolean) {
        this.hasTransactions = hasTransactions
    }

    override val sourceState: Single<TxSourceState>
        get() = Single.just(TxSourceState.NOT_SUPPORTED)

    override val isEnabled: Single<Boolean>
        get() = Single.just(true)

    override val disabledReason: Single<DisabledReason>
        get() = Single.just(DisabledReason.NONE)

    private fun swapItemToSummary(item: SwapTransactionItem): SwapActivitySummaryItem {
        val sendingAccount = this
        return with(item) {
            SwapActivitySummaryItem(
                exchangeRates,
                normaliseTxId(txId),
                timeStampMs,
                sendingValue,
                sendingAccount,
                sendingAddress,
                receivingAddress,
                state,
                direction,
                receivingValue,
                Single.just(CryptoValue(sendingAsset, BigInteger.ZERO)),
                withdrawalNetworkFee,
                asset,
                receivingAsset,
                fiatValue,
                fiatCurrency
            )
        }
    }

    private fun normaliseTxId(txId: String): String =
        txId.replace("-", "")

    fun appendSwapActivity(
        custodialWalletManager: CustodialWalletManager,
        asset: CryptoCurrency,
        directions: List<SwapDirection>,
        activityList: List<ActivitySummaryItem>
    ) = custodialWalletManager.getSwapActivityForAsset(asset, directions)
        .map { swapItems ->
            swapItems.map {
                swapItemToSummary(it)
            }
        }.map { swapActivity ->
            reconcileSwaps(swapActivity, activityList)
        }

    protected abstract fun reconcileSwaps(
        swaps: List<SwapActivitySummaryItem>,
        activity: List<ActivitySummaryItem>
    ): List<ActivitySummaryItem>
}

// To handle Send to PIT
internal class CryptoExchangeAccount(
    override val asset: CryptoCurrency,
    override val label: String,
    private val address: String,
    override val exchangeRates: ExchangeRateDataManager,
    val environmentConfig: EnvironmentConfig
) : CryptoAccountBase() {

    override fun requireSecondPassword(): Single<Boolean> =
        Single.just(false)

    override val accountBalance: Single<Money>
        get() = Single.just(CryptoValue.zero(asset))

    override val actionableBalance: Single<Money>
        get() = accountBalance

    override val receiveAddress: Single<ReceiveAddress>
        get() = Single.just(
            makeExternalAssetAddress(
                asset = asset,
                label = label,
                address = address,
                environmentConfig = environmentConfig,
                postTransactions = onTxCompleted
            )
        )

    override val isDefault: Boolean = false
    override val isFunded: Boolean = false

    override val activity: Single<ActivitySummaryList>
        get() = Single.just(emptyList())

    override val actions: AvailableActions = emptySet()

    // No activity on exchange accounts, so just return the activity list
    // unmodified - they should both be empty anyway
    override fun reconcileSwaps(
        swaps: List<SwapActivitySummaryItem>,
        activity: List<ActivitySummaryItem>
    ): List<ActivitySummaryItem> = activity
}

abstract class CryptoNonCustodialAccount(
    // TODO: Build an interface on PayloadDataManager/PayloadManager for 'global' crypto calls; second password etc?
    protected val payloadDataManager: PayloadDataManager,
    override val asset: CryptoCurrency
) : CryptoAccountBase(), NonCustodialAccount {

    override val isFunded: Boolean = true

    override val actions: AvailableActions
        get() =
            mutableSetOf(
                AssetAction.ViewActivity,
                AssetAction.Send,
                AssetAction.Receive,
                AssetAction.Swap
            ).apply {
                if (!isFunded || isArchived) {
                    remove(AssetAction.Send)
                    remove(AssetAction.Swap)
                }
            }

    override val sourceState: Single<TxSourceState>
        get() = actionableBalance.map {
            if (it.isZero) {
                TxSourceState.NO_FUNDS
            } else {
                TxSourceState.CAN_TRANSACT
            }
        }

    override fun requireSecondPassword(): Single<Boolean> =
        Single.fromCallable { payloadDataManager.isDoubleEncrypted }

    abstract fun createTxEngine(): TxEngine

    val nonCustodialSwapDirections = listOf(SwapDirection.ON_CHAIN, SwapDirection.FROM_USERKEY)

    override val isArchived: Boolean
        get() = false

    override fun reconcileSwaps(
        swaps: List<SwapActivitySummaryItem>,
        activity: List<ActivitySummaryItem>
    ): List<ActivitySummaryItem> {
        val activityList = activity.toMutableList()
        swaps.forEach { swap ->
            val hit = activityList.find {
                it.txId.contains(swap.txId, true)
            } as? NonCustodialActivitySummaryItem

            if (hit?.transactionType == TransactionSummary.TransactionType.SENT) {
                activityList.remove(hit)
                val updatedSwap = swap.copy(
                    depositNetworkFee = hit.fee.first(CryptoValue(hit.cryptoCurrency, BigInteger.ZERO))
                )
                activityList.add(updatedSwap)
            }
        }
        return activityList.toList()
    }
}

// Currently only one custodial account is supported for each asset,
// so all the methods on this can just delegate directly
// to the (required) CryptoSingleAccountCustodialBase

class CryptoAccountCustodialGroup(
    override val label: String,
    override val accounts: SingleAccountList
) : AccountGroup {

    private val account: CryptoAccountBase

    init {
        require(accounts.size == 1)
        require(accounts[0] is CryptoInterestAccount || accounts[0] is CustodialTradingAccount)
        account = accounts[0] as CryptoAccountBase
    }

    override val accountBalance: Single<Money>
        get() = account.accountBalance

    override val pendingBalance: Single<Money>
        get() = account.pendingBalance

    override val activity: Single<ActivitySummaryList>
        get() = account.activity

    override val actions: AvailableActions
        get() = account.actions

    override val isFunded: Boolean
        get() = account.isFunded

    override val hasTransactions: Boolean
        get() = account.hasTransactions

    override fun fiatBalance(
        fiatCurrency: String,
        exchangeRates: ExchangeRates
    ): Single<Money> =
        accountBalance.map { it.toFiat(exchangeRates, fiatCurrency) }

    override fun includes(account: BlockchainAccount): Boolean =
        accounts.contains(account)
}

class CryptoAccountNonCustodialGroup(
    val asset: CryptoCurrency,
    override val label: String,
    override val accounts: SingleAccountList
) : AccountGroup {
    // Produce the sum of all balances of all accounts
    override val accountBalance: Single<Money>
        get() = if (accounts.isEmpty()) {
            Single.just(CryptoValue.zero(asset))
        } else {
            Single.zip(
                accounts.map { it.accountBalance }
            ) { t: Array<Any> ->
                t.map { it as Money }
                    .total()
            }
        }
    override val pendingBalance: Single<Money>
        get() = Single.just(CryptoValue.zero(asset))

    // All the activities for all the accounts
    override val activity: Single<ActivitySummaryList>
        get() = if (accounts.isEmpty()) {
            Single.just(emptyList())
        } else {
            Single.zip(
                accounts.map { it.activity }
            ) { t: Array<Any> ->
                t.filterIsInstance<List<ActivitySummaryItem>>().flatten()
            }
        }

    // The intersection of the actions for each account
    override val actions: AvailableActions
        get() = if (accounts.isEmpty()) {
            emptySet()
        } else {
            accounts.map { it.actions }.reduce { a, b -> a.union(b) }
        }

    // if _any_ of the accounts have transactions
    override val hasTransactions: Boolean
        get() = accounts.map { it.hasTransactions }.any { it }

    // Are any of the accounts funded
    override val isFunded: Boolean = accounts.map { it.isFunded }.any { it }

    override fun fiatBalance(
        fiatCurrency: String,
        exchangeRates: ExchangeRates
    ): Single<Money> =
        if (accounts.isEmpty()) {
            Single.just(FiatValue.zero(fiatCurrency))
        } else {
            accountBalance.map { it.toFiat(exchangeRates, fiatCurrency) }
        }

    override fun includes(account: BlockchainAccount): Boolean =
        accounts.contains(account)
}
