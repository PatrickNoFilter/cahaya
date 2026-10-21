package com.patrisius.streambox.ui

import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter

/** Temporary card presenter so the browse skeleton renders. Replaced in phase 2. */
class PlaceholderCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(320, 180)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        (viewHolder.view as TextView).text = item.toString()
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
