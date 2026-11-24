package org.draken.usagi.core.network.webview

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.util.ext.printStackTraceDebug
import tsuki.network.CloudFlareHelper
import java.io.IOException
import kotlin.coroutines.Continuation

/**
 * Loads a Cloudflare-protected page in the shared WebView and completes only once the challenge
 * is actually gone. A finished page is not enough: Cloudflare serves its challenge at the
 * requested URL, so the URL alone cannot tell a wall from the real content. The document is
 * inspected instead, and a Turnstile widget that asks for a tap is clicked for the user.
 */
class CaptchaContinuationClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	continuation: Continuation<Unit>,
) : ContinuationResumeWebViewClient(continuation) {
	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
	private val handler = Handler(Looper.getMainLooper())
	private var lastView: WebView? = null
	private var pageFinished = false
	private var finished = false
	private var turnstileClicks = 0

	override fun onPageFinished(
		view: WebView?,
		url: String?,
	) {
		pageFinished = true
		checkResolved(view)
	}

	override fun onPageStarted(
		view: WebView?,
		url: String?,
		favicon: Bitmap?,
	) {
		pageFinished = false
		scheduleCheck(view ?: lastView)
	}

	override fun onPageCommitVisible(
		view: WebView,
		url: String,
	) {
		checkResolved(view)
	}

	override fun onReceivedError(
		view: WebView?,
		request: WebResourceRequest?,
		error: WebResourceError?,
	) {
		if (request?.isForMainFrame != true) return
		// A load that was replaced or stopped reports as an unknown error, not a real failure
		if (error?.errorCode == WebViewClient.ERROR_UNKNOWN) return
		fail(view, IOException("Failed to load ${request.url}: ${error?.description}"))
	}

	private fun fail(
		view: WebView?,
		error: Throwable,
	) {
		if (finished) return
		finished = true
		handler.removeCallbacksAndMessages(null)
		super.resumeContinuationWithException(view, error)
	}

	override fun resumeContinuation(view: WebView?) {
		if (finished) return
		finished = true
		handler.removeCallbacksAndMessages(null)
		super.resumeContinuation(view)
	}

	private fun checkResolved(view: WebView?) {
		if (finished) return
		if (view != null) lastView = view
		if (!isContinuationActive()) {
			stop()
			return
		}
		if (clearanceChanged()) {
			resumeContinuation(view)
			return
		}
		val webView = view ?: lastView ?: return scheduleCheck(null)
		webView.evaluateJavascript(CHALLENGE_PROBE_SCRIPT) { result ->
			runCatching { handleProbe(webView, result) }
				.onFailure {
					it.printStackTraceDebug()
					scheduleCheck(webView)
				}
		}
	}

	private fun handleProbe(
		webView: WebView,
		result: String?,
	) {
		if (finished) return
		if (!isContinuationActive()) {
			stop()
			return
		}
		if (clearanceChanged()) {
			resumeContinuation(webView)
			return
		}
		val probe = parseChallengeProbe(result)
		when {
			probe.isPending -> {
				if (probe.hasTurnstile && !probe.hasToken) {
					probe.turnstileCenter?.let { center ->
						if (turnstileClicks < MAX_TURNSTILE_CLICKS) {
							turnstileClicks++
							clickAt(webView, center)
						}
					}
				}
				scheduleCheck(webView)
			}

			pageFinished && webView.url?.let { it.isChallengeUrl() || it.toHttpUrlOrNull() == null } == false -> {
				resumeContinuation(webView)
			}

			else -> {
				scheduleCheck(webView)
			}
		}
	}

	private fun scheduleCheck(view: WebView?) {
		if (finished || !isContinuationActive()) return
		handler.removeCallbacksAndMessages(null)
		handler.postDelayed({ checkResolved(view) }, POLL_INTERVAL)
	}

	private fun stop() {
		finished = true
		handler.removeCallbacksAndMessages(null)
	}

	private fun clearanceChanged(): Boolean {
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		return clearance != null && clearance != oldClearance
	}

	private fun clickAt(
		view: WebView,
		center: Pair<Int, Int>,
	) {
		val x = center.first.toFloat()
		val y = center.second.toFloat()
		val now = SystemClock.uptimeMillis()
		val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
		val up = MotionEvent.obtain(now, now + CLICK_HOLD, MotionEvent.ACTION_UP, x, y, 0)
		runCatching {
			view.dispatchTouchEvent(down)
			view.dispatchTouchEvent(up)
		}
		down.recycle()
		up.recycle()
	}

	private companion object {
		private const val POLL_INTERVAL = 400L
		private const val CLICK_HOLD = 60L
		private const val MAX_TURNSTILE_CLICKS = 3
	}
}

internal fun String.isChallengeUrl(): Boolean {
	val value = lowercase()
	return value.contains("/cdn-cgi/challenge-platform/") ||
		value.contains("/cdn-cgi/l/chk_captcha") ||
		value.contains("challenges.cloudflare.com")
}
