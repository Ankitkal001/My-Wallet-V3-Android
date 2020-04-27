package piuk.blockchain.android.ui.activity.adapter

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.extensions.LayoutContainer
import kotlinx.android.synthetic.main.item_activity_detail_info.view.*
import piuk.blockchain.android.R
import piuk.blockchain.android.ui.activity.detail.Action
import piuk.blockchain.android.ui.activity.detail.ActivityDetailsType
import piuk.blockchain.android.ui.activity.detail.Amount
import piuk.blockchain.android.ui.activity.detail.Created
import piuk.blockchain.android.ui.activity.detail.Description
import piuk.blockchain.android.ui.activity.detail.Fee
import piuk.blockchain.android.ui.activity.detail.Value
import piuk.blockchain.android.ui.adapters.AdapterDelegate
import piuk.blockchain.android.util.extensions.toFormattedDate
import piuk.blockchain.androidcoreui.utils.extensions.inflate

class ActivityDetailInfoItemDelegate <in T> : AdapterDelegate<T> {
    override fun isForViewType(items: List<T>, position: Int): Boolean {
        val item = items[position] as ActivityDetailsType
        return item !is Action && item !is Description
    }

    override fun onCreateViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
        InfoItemViewHolder(parent.inflate(R.layout.item_activity_detail_info))

    override fun onBindViewHolder(
        items: List<T>,
        position: Int,
        holder: RecyclerView.ViewHolder
    ) = (holder as InfoItemViewHolder).bind(
        items[position] as ActivityDetailsType
    )
}

private class InfoItemViewHolder(var parent: View) : RecyclerView.ViewHolder(parent), LayoutContainer {
    override val containerView: View?
        get() = itemView

    fun bind(item: ActivityDetailsType) {
        itemView.item_activity_detail_title.text = getHeaderForType(item)
        itemView.item_activity_detail_description.text = getValueForType(item)
    }

    private fun getHeaderForType(infoType: ActivityDetailsType): String =
        parent.context.getString(
            when (infoType) {
                is Created -> R.string.activity_details_created
                is Amount -> R.string.activity_details_amount
                is Fee -> R.string.activity_details_fee
                is Value -> R.string.activity_details_value
                else -> R.string.activity_details_empty
            }
        )

    private fun getValueForType(infoType: ActivityDetailsType): String =
        when (infoType) {
            is Created -> infoType.date.toFormattedDate()
            is Amount -> infoType.cryptoValue.toStringWithSymbol()
            is Fee -> infoType.feeValue.toStringWithSymbol()
            is Value -> infoType.fiatAtExecution.toStringWithSymbol()
            else -> ""
        }
}
