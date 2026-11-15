package de.mischpult

import android.app.Activity
import android.app.Application
import android.os.Bundle
import de.mischpult.library.CrateStore

class MischpultApp : Application() {

    lateinit var crates: CrateStore
        private set
    lateinit var controller: DjController
        private set

    /** True while one of our activities is started (visible). */
    @Volatile var inForeground = false
        private set
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        crates = CrateStore(filesDir)
        controller = DjController(this, crates)
        PlaybackService.clearStale(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                inForeground = ++startedActivities > 0
            }
            override fun onActivityStopped(activity: Activity) {
                inForeground = --startedActivities > 0
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        lateinit var instance: MischpultApp
            private set
    }
}
