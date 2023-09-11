package eu.kanade.tachiyomi.extension.en.koushoku

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://ksk.moe/xxxxx/xxxxx intents and redirects them to
 * the main Tachiyomi process.
 */
class KoushokuUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        val parameters = intent?.data?.query ?: ""
        Log.v("KoushokuUrlActivity", "Segments: $pathSegments")
        Log.v("KoushokuUrlActivity", "Params: $parameters")
        if (pathSegments != null && pathSegments.size > 1) {
            Log.v("KoushokuUrlActivity", "Creating Intent")
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                with(pathSegments[0]) {
                    when {
                        equals("view") || equals("read") -> putExtra(
                            "query",
                            "id:${pathSegments[1]}/${pathSegments[2]}",
                        )
                        equals("artists") ||
                            equals("circles") ||
                            equals("magazines") ||
                            equals("tags") ||
                            equals("parodies") -> putExtra(
                            "query",
                            "${pathSegments[0][0]}:\"${pathSegments[1]}\"${Koushoku.PARAMS_PREFIX}$parameters",
                        )
                        else -> putExtra("query", "")
                    }
                }
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("KoushokuUrlActivity", e.toString())
            }
        } else {
            Log.e("KoushokuUrlActivity", "Could not parse URI from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
