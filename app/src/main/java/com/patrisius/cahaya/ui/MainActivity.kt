package com.patrisius.cahaya.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/** Leanback entry point. Hosts [BrowseFragment] full-screen. */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, BrowseFragment())
                .commit()
        }
    }
}
