package piuk.blockchain.android.ui.buysell.payment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.CoordinatorLayout
import android.support.v4.content.ContextCompat
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import com.jakewharton.rxbinding2.widget.RxTextView
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.android.synthetic.main.activity_buy_sell_build_order.*
import kotlinx.android.synthetic.main.toolbar_general.*
import piuk.blockchain.android.R
import piuk.blockchain.android.injection.Injector
import piuk.blockchain.android.ui.buysell.payment.models.OrderType
import piuk.blockchain.android.util.extensions.CompositeSubscription
import piuk.blockchain.android.util.extensions.addToCompositeDisposable
import piuk.blockchain.androidcore.data.currency.toSafeDouble
import piuk.blockchain.androidcore.utils.helperfunctions.consume
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import piuk.blockchain.androidcoreui.ui.base.BaseMvpActivity
import piuk.blockchain.androidcoreui.ui.customviews.MaterialProgressDialog
import piuk.blockchain.androidcoreui.ui.customviews.NumericKeyboardCallback
import piuk.blockchain.androidcoreui.ui.customviews.ToastCustom
import piuk.blockchain.androidcoreui.utils.extensions.disableSoftKeyboard
import piuk.blockchain.androidcoreui.utils.extensions.gone
import piuk.blockchain.androidcoreui.utils.extensions.invisible
import piuk.blockchain.androidcoreui.utils.extensions.invisibleIf
import piuk.blockchain.androidcoreui.utils.extensions.toast
import piuk.blockchain.androidcoreui.utils.extensions.visible
import piuk.blockchain.androidcoreui.utils.helperfunctions.onItemSelectedListener
import timber.log.Timber
import java.text.DecimalFormatSymbols
import java.util.*
import javax.inject.Inject
import kotlinx.android.synthetic.main.activity_buy_sell_build_order.button_review_order as buttonReviewOrder
import kotlinx.android.synthetic.main.activity_buy_sell_build_order.edit_text_receive_amount as editTextReceive
import kotlinx.android.synthetic.main.activity_buy_sell_build_order.edit_text_send_amount as editTextSend
import kotlinx.android.synthetic.main.activity_buy_sell_build_order.progress_bar_quote_rate as progressBarQuote
import kotlinx.android.synthetic.main.activity_buy_sell_build_order.spinner_currency_selection as spinnerCurrencySelection
import kotlinx.android.synthetic.main.activity_buy_sell_build_order.text_view_limits as textViewLimits
import kotlinx.android.synthetic.main.activity_buy_sell_build_order.text_view_quote_price as textViewQuotePrice


class BuySellBuildOrderActivity :
        BaseMvpActivity<BuySellBuildOrderView, BuySellBuildOrderPresenter>(), BuySellBuildOrderView,
        CompositeSubscription,
        NumericKeyboardCallback {

    @Inject lateinit var presenter: BuySellBuildOrderPresenter
    override val locale: Locale = Locale.getDefault()
    private var progressDialog: MaterialProgressDialog? = null
    override val compositeDisposable = CompositeDisposable()
    override val orderType by unsafeLazy { intent.getSerializableExtra(EXTRA_ORDER_TYPE) as OrderType }

    private val defaultDecimalSeparator =
            DecimalFormatSymbols.getInstance().decimalSeparator.toString()
    private val editTexts by unsafeLazy {
        listOf(editTextSend, editTextReceive)
    }

    init {
        Injector.INSTANCE.presenterComponent.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buy_sell_build_order)
        require(intent.hasExtra(EXTRA_ORDER_TYPE)) { "You must pass an order type to the Activity. Please start this Activity via the provided static factory method." }

        val title = when (orderType) {
            OrderType.Buy -> R.string.buy_sell_buy
            OrderType.Sell -> R.string.buy_sell_sell
        }

        setupToolbar(toolbar_general, title)

        val sendObservable = getTextWatcherObservable(editTextSend, presenter.sendSubject)
        val receiveObservable = getTextWatcherObservable(editTextReceive, presenter.receiveSubject)

        sendObservable
                .onErrorResumeNext(sendObservable)
                .addToCompositeDisposable(this)
                .subscribe()

        receiveObservable
                .onErrorResumeNext(receiveObservable)
                .addToCompositeDisposable(this)
                .subscribe()

        setupKeypad()

        onViewReady()
    }

    override fun renderExchangeRate(status: BuySellBuildOrderPresenter.ExchangeRateStatus) {
        when (status) {
            is BuySellBuildOrderPresenter.ExchangeRateStatus.Data -> renderExchangeRateData(status)
            BuySellBuildOrderPresenter.ExchangeRateStatus.Loading -> renderExchangeRateLoading()
            BuySellBuildOrderPresenter.ExchangeRateStatus.Failed -> renderExchangeRateFailure()
        }
        clearEditTexts()
    }

    private fun getTextWatcherObservable(
            editText: EditText,
            publishSubject: PublishSubject<String>
    ): Observable<String> = RxTextView.textChanges(editText)
            // Logging
            .doOnError(Timber::e)
            .doOnTerminate { Timber.wtf("Text watcher terminated unexpectedly $editText") }
            // Skip first event emitted when subscribing
            .skip(1)
            // Convert to String
            .map { it.toString() }
            // Ignore elements emitted by non-user events (ie presenter updates) and those
            // emitted from changes to paired EditText (ie edit fiat, edit crypto)
            .doOnNext { if (currentFocus == editText) publishSubject.onNext(it) }
            // Scheduling
            .subscribeOn(Schedulers.computation())
            .observeOn(AndroidSchedulers.mainThread())

    private fun renderExchangeRateFailure() {
        textViewQuotePrice.invisible()
        showQuoteInProgress(false)
        toast(R.string.buy_sell_error_fetching_quote, ToastCustom.TYPE_ERROR)
    }

    private fun renderExchangeRateData(status: BuySellBuildOrderPresenter.ExchangeRateStatus.Data) {
        textViewQuotePrice.text = status.formattedQuote
        textViewQuotePrice.visible()
        showQuoteInProgress(false)
    }

    private fun renderExchangeRateLoading() {
        textViewQuotePrice.invisible()
        showQuoteInProgress(true)
    }

    override fun renderSpinnerStatus(status: BuySellBuildOrderPresenter.SpinnerStatus) {
        when (status) {
            is BuySellBuildOrderPresenter.SpinnerStatus.Data -> setupSpinner(status.currencies)
            BuySellBuildOrderPresenter.SpinnerStatus.Loading -> displayProgressDialog()
            BuySellBuildOrderPresenter.SpinnerStatus.Failure -> renderCurrencyFetchFailure()
        }
    }

    override fun renderSellLimit(status: BuySellBuildOrderPresenter.LimitStatus) {
        when (status) {
            is BuySellBuildOrderPresenter.LimitStatus.Data -> {

                val limit = status.limit
                val text = resources.getString(status.textResourceId, limit)

                val spannable = SpannableString(text)

                textViewLimits.setText(spannable, TextView.BufferType.SPANNABLE)

                textViewLimits.setOnClickListener {}

                dismissProgressDialog()
            }
            BuySellBuildOrderPresenter.LimitStatus.Loading -> displayProgressDialog()
            BuySellBuildOrderPresenter.LimitStatus.Failure -> renderLimitFetchFailure()
        }
    }

    override fun renderBuyLimit(status: BuySellBuildOrderPresenter.LimitStatus) {
        when (status) {
            is BuySellBuildOrderPresenter.LimitStatus.Data -> {

                val limit = status.limit
                val text = resources.getString(status.textResourceId, limit)

                val start = text.indexOf(limit)
                val end = start + limit.length

                val spannable = SpannableString(text)

                spannable.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(this, R.color.primary_blue_accent)),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                textViewLimits.setText(spannable, TextView.BufferType.SPANNABLE)

                textViewLimits.setOnClickListener {
                    val parsed = limit.toSafeDouble(locale)
                    editTextSend.setText("$parsed")
                }

                dismissProgressDialog()
            }
            BuySellBuildOrderPresenter.LimitStatus.Loading -> displayProgressDialog()
            BuySellBuildOrderPresenter.LimitStatus.Failure -> renderLimitFetchFailure()
        }
    }

    override fun updateReceiveAmount(amount: String) {
        editTextReceive.setText(amount)
    }

    override fun updateSendAmount(amount: String) {
        editTextSend.setText(amount)
    }

    override fun setButtonEnabled(enabled: Boolean) {
        buttonReviewOrder.isEnabled = enabled
    }

    override fun clearEditTexts() {
        editTextSend.text.clear()
        editTextReceive.text.clear()
    }

    override fun showToast(message: Int, toastType: String) {
        toast(message, toastType)
    }

    override fun showQuoteInProgress(inProgress: Boolean) {
        progressBarQuote.invisibleIf { !inProgress }
    }

    override fun onFatalError() {
        toast(R.string.unexpected_error, ToastCustom.TYPE_ERROR)
        finish()
    }

    private fun setupSpinner(currencies: List<String>) {
        val dataAdapter = ArrayAdapter<String>(this, R.layout.item_spinner_buy_sell, currencies)
                .apply { setDropDownViewResource(R.layout.item_spinner_buy_sell_list) }

        with(spinnerCurrencySelection) {
            adapter = dataAdapter
            setSelection(0, false)
            onItemSelectedListener = onItemSelectedListener {
                presenter.onCurrencySelected(currencies[it])
            }
        }
        dismissProgressDialog()
    }

    private fun renderCurrencyFetchFailure() {
        dismissProgressDialog()
        toast(R.string.buy_sell_error_fetching_quote, ToastCustom.TYPE_ERROR)
        finish()
    }

    private fun renderLimitFetchFailure() {
        dismissProgressDialog()
        toast(R.string.buy_sell_error_fetching_limit, ToastCustom.TYPE_ERROR)
        finish()
    }

    private fun displayProgressDialog() {
        if (!isFinishing) {
            progressDialog = MaterialProgressDialog(this).apply {
                setMessage(getString(R.string.please_wait))
                setCancelable(false)
                show()
            }
        }
    }

    private fun dismissProgressDialog() {
        if (progressDialog?.isShowing == true) {
            progressDialog!!.dismiss()
            progressDialog = null
        }
    }

    override fun onSupportNavigateUp(): Boolean = consume { onBackPressed() }

    override fun onDestroy() {
        compositeDisposable.clear()
        super.onDestroy()
    }

    private fun isKeyboardVisible(): Boolean = buysell_keyboard.isVisible

    override fun onBackPressed() {
        if (isKeyboardVisible()) {
            closeKeyPad()
        } else {
            super.onBackPressed()
        }
    }

    private fun closeKeyPad() {
        buysell_keyboard.setNumpadVisibility(View.GONE)
    }

    private fun setupKeypad() {
        editTexts.forEach {
            it.disableSoftKeyboard()
            buysell_keyboard.enableOnView(it)
        }
        buysell_keyboard.apply {
            setDecimalSeparator(defaultDecimalSeparator)
            setCallback(this@BuySellBuildOrderActivity)
        }
    }

    override fun onKeypadClose() {
        val height = resources.getDimension(R.dimen.action_bar_height).toInt()
        // Resize activity to default
        buysell_scrollview.apply {
            setPadding(0, 0, 0, 0)
            layoutParams = CoordinatorLayout.LayoutParams(
                    CoordinatorLayout.LayoutParams.MATCH_PARENT,
                    CoordinatorLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(0, height, 0, 0) }

            postDelayed({ smoothScrollTo(0, 0) }, 100)
        }

        buttonReviewOrder.visible()
    }

    override fun onKeypadOpen() = Unit

    override fun onKeypadOpenCompleted() {
        // Resize activity around view
        val height = resources.getDimension(R.dimen.action_bar_height).toInt()
        buysell_scrollview.apply {
            setPadding(0, 0, 0, buysell_keyboard.height)
            layoutParams = CoordinatorLayout.LayoutParams(
                    CoordinatorLayout.LayoutParams.MATCH_PARENT,
                    CoordinatorLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(0, height, 0, 0) }

            scrollTo(0, bottom)
        }

        buttonReviewOrder.gone()
    }

    override fun createPresenter(): BuySellBuildOrderPresenter = presenter

    override fun getView(): BuySellBuildOrderView = this

    companion object {

        private const val EXTRA_ORDER_TYPE =
                "piuk.blockchain.android.ui.buysell.payment.EXTRA_ORDER_TYPE"

        fun start(context: Context, orderType: OrderType) {
            Intent(context, BuySellBuildOrderActivity::class.java)
                    .apply { putExtra(EXTRA_ORDER_TYPE, orderType) }
                    .run { context.startActivity(this) }
        }

    }

}