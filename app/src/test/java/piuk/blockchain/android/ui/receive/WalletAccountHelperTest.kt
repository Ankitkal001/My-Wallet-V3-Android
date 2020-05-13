package piuk.blockchain.android.ui.receive

import com.blockchain.logging.CrashLogger
import com.blockchain.sunriver.XlmDataManager
import com.blockchain.testutils.bitcoin
import com.blockchain.testutils.bitcoinCash
import com.blockchain.testutils.ether
import com.blockchain.testutils.lumens
import com.nhaarman.mockito_kotlin.atLeastOnce
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import info.blockchain.balance.AccountReference
import info.blockchain.balance.CryptoCurrency
import info.blockchain.wallet.coin.GenericMetadataAccount
import info.blockchain.wallet.ethereum.EthereumAccount
import info.blockchain.wallet.payload.PayloadManager
import info.blockchain.wallet.payload.data.Account
import info.blockchain.wallet.payload.data.AddressBook
import info.blockchain.wallet.payload.data.LegacyAddress
import info.blockchain.wallet.payload.data.archive
import io.reactivex.Single
import org.amshove.kluent.`it returns`
import org.amshove.kluent.`should be`
import org.amshove.kluent.`should equal`
import org.bitcoinj.params.BitcoinCashMainNetParams
import org.bitcoinj.params.BitcoinMainNetParams
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import piuk.blockchain.android.R
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.androidcore.data.api.EnvironmentConfig
import piuk.blockchain.androidcore.data.bitcoincash.BchDataManager
import piuk.blockchain.androidcore.data.erc20.Erc20Account
import piuk.blockchain.androidcore.data.ethereum.EthDataManager
import piuk.blockchain.androidcore.data.ethereum.models.CombinedEthModel
import java.math.BigInteger
import java.util.Locale

class WalletAccountHelperTest {

    private lateinit var subject: WalletAccountHelper
    private val payloadManager: PayloadManager = mock(defaultAnswer = Mockito.RETURNS_DEEP_STUBS)
    private val stringUtils: StringUtils = mock()
    private val ethDataManager: EthDataManager = mock(defaultAnswer = Mockito.RETURNS_DEEP_STUBS)
    private val bchDataManager: BchDataManager = mock(defaultAnswer = Mockito.RETURNS_DEEP_STUBS)
    private val xlmDataManager: XlmDataManager = mock(defaultAnswer = Mockito.RETURNS_DEEP_STUBS)
    private val environmentSettings: EnvironmentConfig = mock()
    private val paxAccount: Erc20Account = mock()
    private val crashLogger: CrashLogger = mock()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)

        subject = WalletAccountHelper(
            payloadManager,
            stringUtils,
            ethDataManager,
            paxAccount,
            bchDataManager,
            xlmDataManager,
            environmentSettings,
            crashLogger
        )

        whenever(environmentSettings.bitcoinCashNetworkParameters)
            .thenReturn(BitcoinCashMainNetParams.get())
        whenever(environmentSettings.bitcoinNetworkParameters)
            .thenReturn(BitcoinMainNetParams.get())

        whenever(stringUtils.getString(R.string.watch_only)).thenReturn("watch only")
        whenever(stringUtils.getString(R.string.address_book_label)).thenReturn("address book")
    }

    @Test
    fun `getAccountItems should return one Account and one LegacyAddress`() {
        // Arrange
        val label = "LABEL"
        val xPub = "X_PUB"
        val address = "ADDRESS"
        val account = Account().apply {
            this.label = label
            this.xpub = xPub
        }
        val legacyAddress = LegacyAddress().apply {
            this.label = null
            this.address = address
        }
        whenever(payloadManager.payload?.hdWallets?.get(0)?.accounts).thenReturn(listOf(account))
        whenever(payloadManager.payload?.legacyAddressList).thenReturn(mutableListOf(legacyAddress))

        whenever(payloadManager.getAddressBalance(xPub)).thenReturn(1.2.bitcoin().amount)
        whenever(payloadManager.getAddressBalance(address)).thenReturn(2.3.bitcoin().amount)
        // Act
        val result = subject.accountItems(CryptoCurrency.BTC)
            .test()
            .assertComplete()
            .assertNoErrors()
            .values()
            .single()

        // Assert
        verify(payloadManager, atLeastOnce()).payload
        result.size `should be` 2
        result[0].accountObject `should be` account
        result[0].balance `should equal` 1.2.bitcoin()
        result[1].accountObject `should be` legacyAddress
        result[1].balance `should equal` 2.3.bitcoin()
    }

    @Test
    fun `getAccountItems when currency is BCH should return one Account and one LegacyAddress`() {
        // Arrange
        val label = "LABEL"
        val xPub = "X_PUB"
        // Must be valid or conversion to BECH32 will fail
        val address = "17MgvXUa6tPsh3KMRWAPYBuDwbtCBF6Py5"
        val account = GenericMetadataAccount().apply {
            this.label = label
            this.xpub = xPub
        }
        val legacyAddress = LegacyAddress().apply {
            this.label = null
            this.address = address
        }
        whenever(bchDataManager.getActiveAccounts()).thenReturn(listOf(account))
        whenever(bchDataManager.getAddressBalance(address)).thenReturn(5.1.bitcoinCash().amount)
        whenever(payloadManager.payload?.legacyAddressList).thenReturn(mutableListOf(legacyAddress))
        whenever(bchDataManager.getAddressBalance(xPub)).thenReturn(20.1.bitcoinCash().amount)

        // Act
        val result = subject.accountItems(CryptoCurrency.BCH)
            .test()
            .assertComplete()
            .assertNoErrors()
            .values()
            .single()

        // Assert
        verify(payloadManager, atLeastOnce()).payload
        verify(bchDataManager).getActiveAccounts()
        verify(bchDataManager, atLeastOnce()).getAddressBalance(address)
        result.size `should be` 2
        result[0].accountObject `should be` account
        result[0].balance `should equal` 20.1.bitcoinCash()
        result[1].accountObject `should be` legacyAddress
        result[1].balance `should equal` 5.1.bitcoinCash()
    }

    @Test
    fun `getHdAccounts should return single Account`() {
        // Arrange
        val label = "LABEL"
        val xPub = "X_PUB"
        val archivedAccount = Account().apply { isArchived = true }
        val account = Account().apply {
            this.label = label
            this.xpub = xPub
        }
        whenever(payloadManager.payload?.hdWallets?.get(0)?.accounts)
            .thenReturn(mutableListOf(archivedAccount, account))

        whenever(payloadManager.getAddressBalance(xPub)).thenReturn(BigInteger.TEN)
        // Act
        val result = subject.accountItems(CryptoCurrency.BTC)
            .test()
            .assertComplete()
            .assertNoErrors()
            .values()
            .single()

        // Assert
        verify(payloadManager, atLeastOnce()).payload
        result.size `should equal` 1
        result[0].accountObject `should be` account
        result[0].balance `should equal` 0.0000001.bitcoin()
    }

    @Test
    fun `getHdAccounts when currency is BCH should return single Account`() {
        // Arrange
        val label = "LABEL"
        val xPub = "X_PUB"
        val archivedAccount = GenericMetadataAccount().apply { isArchived = true }
        val account = GenericMetadataAccount().apply {
            this.label = label
            this.xpub = xPub
        }
        whenever(bchDataManager.getActiveAccounts())
            .thenReturn(mutableListOf(archivedAccount, account))

        whenever(bchDataManager.getAddressBalance(xPub)).thenReturn(BigInteger.TEN)

        // Act
        val result = subject.accountItems(CryptoCurrency.BCH)
            .test()
            .assertComplete()
            .assertNoErrors()
            .values()
            .single()

        // Assert
        verify(bchDataManager).getActiveAccounts()
        result.size `should equal` 1
        result[0].accountObject `should be` account
        result[0].balance `should equal` 0.0000001.bitcoinCash()
    }

    @Test
    fun `getAccountItems when currency is ETH should return one account`() {
        // Arrange
        val ethAccount: EthereumAccount = mock()
        val combinedEthModel: CombinedEthModel = mock()

        whenever(ethDataManager.getEthWallet()?.account).thenReturn(ethAccount)
        whenever(ethAccount.address).thenReturn("address")
        whenever(ethAccount.label).thenReturn("")
        whenever(ethDataManager.getEthResponseModel()).thenReturn(combinedEthModel)
        whenever(combinedEthModel.getTotalBalance()).thenReturn(99.1.ether().amount)

        // Act
        val result = subject.accountItems(CryptoCurrency.ETHER)
            .test()
            .assertComplete()
            .assertNoErrors()
            .values()
            .single()

        // Assert
        verify(ethDataManager, atLeastOnce()).getEthWallet()
        result.size `should be` 1
        result[0].accountObject `should equal` ethAccount
        result[0].balance `should equal` 99.1.ether()
    }

    @Test
    fun `getAccountItems when currency is XLM should return one account`() {
        // Arrange
        whenever(xlmDataManager.defaultAccount()) `it returns`
                Single.just(
                    AccountReference.Xlm(
                        "My Xlm account",
                        "address"
                    )
                )
        whenever(xlmDataManager.getBalance()) `it returns` Single.just(123.lumens())
        // Act
        val result = subject.accountItems(CryptoCurrency.XLM)
            .test().values().single()

        // Assert
        result.size `should be` 1
        result[0].label `should equal` "My Xlm account"
        result[0].balance `should equal` 123.lumens()
    }

    @Test
    fun `getLegacyAddresses should return single LegacyAddress`() {
        // Arrange
        val address = "ADDRESS"
        val archivedAddress = LegacyAddress().apply { archive() }
        val legacyAddress = LegacyAddress().apply {
            this.label = null
            this.address = address
        }
        whenever(payloadManager.payload?.legacyAddressList)
            .thenReturn(mutableListOf(archivedAddress, legacyAddress))

        whenever(payloadManager.getAddressBalance(address)).thenReturn(BigInteger.TEN)

        // Act
        val result = subject.getLegacyBtcAddresses()

        // Assert
        verify(payloadManager, atLeastOnce()).payload
        result.size `should equal` 1
        result[0].accountObject `should be` legacyAddress
        result[0].balance `should equal` 0.0000001.bitcoin()
    }

    @Test
    fun `getAddressBookEntries should return single item`() {
        // Arrange
        val addressBook = AddressBook()
        whenever(payloadManager.payload?.addressBook).thenReturn(listOf(addressBook))
        // Act
        val result = subject.getAddressBookEntries()
        // Assert
        result.size `should equal` 1
    }

    @Test
    fun `getAddressBookEntries should return empty list`() {
        // Arrange
        whenever(payloadManager.payload?.addressBook)
            .thenReturn(null)
        // Act
        val result = subject.getAddressBookEntries()
        // Assert
        result.size `should equal` 0
    }
}
