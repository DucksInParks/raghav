package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TheMoviesFlixPlugin : Plugin() {
    override fun load(context: Context) {
        initTMFCFBypass(context)
        registerMainAPI(TheMoviesFlix())
        registerExtractorAPI(FastDlExtractor())
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(GoFileExtractor())
        registerExtractorAPI(FileBeeExtractor())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                TheMoviesFlixSettingsFragment().show(activity.supportFragmentManager, "TheMoviesFlixSettings")
            }
            kotlin.Unit
        }
    }
}
