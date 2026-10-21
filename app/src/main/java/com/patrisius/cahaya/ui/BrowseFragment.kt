package com.patrisius.cahaya.ui

import android.os.Bundle
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import com.patrisius.cahaya.CahayaApp

/**
 * Phase 2 skeleton: the TV home screen.
 *
 * Planned rows: one ListRow per installed addon's catalogs ("Popular movies",
 * "Top series", …) plus Library and Settings rows. Card clicks open a details
 * screen; play clicks resolve streams via AddonManager.streamsFor() and hand
 * the URL + subtitles to PlaybackActivity.
 */
class BrowseFragment : BrowseSupportFragment() {

    private lateinit var backgroundManager: BackgroundManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = "Cahaya"          // TODO: real name from strings.xml
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        backgroundManager = BackgroundManager.getInstance(requireActivity())
        backgroundManager.attach(requireActivity().window)

        // Placeholder rows until the addon engine is wired in.
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = PlaceholderCardPresenter()
        listOf("Movies", "Series", "My Library", "Addons").forEachIndexed { i, name ->
            val list = ArrayObjectAdapter(cardPresenter)
            repeat(5) { list.add("Placeholder ${i + 1}.${it + 1}") }
            rowsAdapter.add(ListRow(HeaderItem(i.toLong(), name), list))
        }
        adapter = rowsAdapter

        // TODO phase 2: replace placeholders —
        //   val mgr = (requireContext().applicationContext as CahayaApp).addonManager
        //   mgr.catalogAddons("movie").forEach { client ->
        //       client.manifest?.catalogs?.forEach { catalog ->
        //           rowsAdapter.add(ListRow(HeaderItem(...), cards(client.catalog(...))))
        //       }
        //   }
    }

    override fun onDestroy() {
        backgroundManager.release()
        super.onDestroy()
    }
}
