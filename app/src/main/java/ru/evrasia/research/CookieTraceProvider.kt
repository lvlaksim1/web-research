package ru.evrasia.research

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

class CookieTraceProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var browserRef = WeakReference<WebResearchV10Activity>(null)
    private val traceEngine = CookieTraceEngine()

    private val ticker = object : Runnable {
        override fun run() {
            browserRef.get()?.let { traceEngine.sample(it) }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(this)
        handler.post(ticker)
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is WebResearchV10Activity) {
            browserRef = WeakReference(activity)
            traceEngine.reset()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is WebResearchV10Activity) {
            browserRef = WeakReference(activity)
            traceEngine.onBrowserResumed(activity)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is WebResearchV10Activity && browserRef.get() === activity) {
            browserRef.clear()
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
