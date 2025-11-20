package com.redowan

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.redowan.HubCloud

@CloudstreamPlugin
class SkymoviesHDPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(SkymoviesHDProvider())
        registerExtractorAPI(HubCloud())
    }
}