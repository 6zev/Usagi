package org.draken.usagi.core.parser.external

import android.app.Activity
import android.app.Application
import org.draken.usagi.core.ui.DefaultActivityLifecycleCallbacks
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands the resumed Activity over to extension code.
 *
 * Extensions are loaded in their own classloader and build their own keiyoushi `ActivityTracker`,
 * which finds the foreground Activity by registering lifecycle callbacks. That registration only
 * sees Activities that resume *after* the first call, and its reflective fallback (`ActivityThread`)
 * is blocked by the hidden API policy, so the very first Turnstile that needs a window finds
 * nothing and fails with "Captcha needs interaction, but the app isn't in the foreground".
 *
 * keiyoushi's tracker shares the Activity through a system property across classloaders. Providing
 * that same reference from the host, and keeping it current, makes `topActivity()` resolve on the
 * first try.
 */
object ExtensionActivityTracker {
	private const val PROPERTY = "keiyoushi.activity-tracker.current"

	private val resumed = AtomicReference<WeakReference<Activity>?>(null)

	fun install(application: Application) {
		System.getProperties()[PROPERTY] = resumed
		application.registerActivityLifecycleCallbacks(
			object : DefaultActivityLifecycleCallbacks {
				override fun onActivityResumed(activity: Activity) {
					resumed.set(WeakReference(activity))
				}

				override fun onActivityPaused(activity: Activity) {
					if (resumed.get()?.get() === activity) resumed.set(null)
				}

				override fun onActivityDestroyed(activity: Activity) {
					if (resumed.get()?.get() === activity) resumed.set(null)
				}
			},
		)
	}
}
