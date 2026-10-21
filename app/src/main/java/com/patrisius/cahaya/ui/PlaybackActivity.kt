package com.patrisius.cahaya.ui

import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.patrisius.cahaya.addon.AddonClient

/**
 * Phase 2 skeleton: Media3 playback.
 *
 * Launch contract (phase 2):
 *   intent.putExtra(EXTRA_STREAM_URL, url)
 *   intent.putExtra(EXTRA_TITLE, title)
 *   intent.putExtra(EXTRA_SUBTITLE_URLS, arrayOf(urls))   // srt/vtt
 *   intent.putExtra(EXTRA_SUBTITLE_LANGS, arrayOf("id","en"))
 */
class PlaybackActivity : FragmentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this)
        setContentView(playerView)

        val dataSourceFactory = OkHttpDataSource.Factory(AddonClient.defaultHttp())
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { playerView.player = it }

        // TODO phase 2: build the MediaItem from intent extras —
        //   val subs = urls.mapIndexed { i, u ->
        //       MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
        //           .setMimeType(MimeTypes.TEXT_VTT)   // or APPLICATION_SUBRIP
        //           .setLanguage(langs[i]).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        //   }
        //   val item = MediaItem.Builder().setUri(streamUrl)
        //       .setSubtitleConfigurations(subs).build()
        //   player.setMediaItem(item); player.prepare(); player.play()
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE_URLS = "subtitle_urls"
        const val EXTRA_SUBTITLE_LANGS = "subtitle_langs"
    }
}
