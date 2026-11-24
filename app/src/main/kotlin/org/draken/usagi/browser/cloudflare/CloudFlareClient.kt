package org.draken.usagi.browser.cloudflare

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.browser.BrowserClient
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.network.webview.CHALLENGE_PROBE_SCRIPT
import org.draken.usagi.core.network.webview.adblock.AdBlock
import org.draken.usagi.core.network.webview.isChallengeUrl
import org.draken.usagi.core.network.webview.parseChallengeProbe
import org.draken.usagi.core.util.ext.printStackTraceDebug
import tsuki.network.CloudFlareHelper

private const val LOOP_COUNTER = 5
private const val RECHECK_INTERVAL = 800L
private const val CLICK_HOLD = 60L
private const val MAX_TURNSTILE_CLICKS = 6

/** How long a page with no challenge on it must stay clean before the check is treated as passed. */
private const val CLEAN_PASS_DELAY = 6_000L
private const val CLEAN_PASS_PROBES = 3

class CloudFlareClient(
	private val cookieJar: MutableCookieJar,
	private val callback: CloudFlareCallback,
	adBlock: AdBlock,
	private val targetUrl: String,
) : BrowserClient(callback, adBlock) {
	private val oldClearance = getClearance()
	private val targetHost = targetUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")
	private var counter = 0
	private var passed = false
	private var turnstileClicks = 0
	private var pageFinished = false
	private var finishedAt = 0L
	private var sawChallenge = false
	private var cleanProbes = 0
	private var lastWebView: WebView? = null
	private val recheck =
		Runnable {
			val view = lastWebView ?: return@Runnable
			if (passed || !view.isAttachedToWindow) return@Runnable
			checkResolved(view)
		}

	override fun onPageStarted(
		view: WebView?,
		url: String?,
		favicon: Bitmap?,
	) {
		super.onPageStarted(view, url, favicon)
		pageFinished = false
		finishedAt = 0L
		sawChallenge = false
		cleanProbes = 0
		turnstileClicks = 0
		checkClearance(url)
	}

	override fun onPageCommitVisible(
		view: WebView,
		url: String,
	) {
		super.onPageCommitVisible(view, url)
		callback.onPageLoaded()
	}

	override fun onPageFinished(
		webView: WebView,
		url: String,
	) {
		super.onPageFinished(webView, url)
		pageFinished = true
		finishedAt = SystemClock.elapsedRealtime()
		callback.onPageLoaded()
		checkResolved(webView)
	}

	fun reset() {
		counter = 0
		turnstileClicks = 0
		cleanProbes = 0
		sawChallenge = false
	}

	private fun checkClearance(url: String?) {
		if (clearanceChanged()) {
			pass()
			return
		}
		if (url?.isChallengeUrl() == true) {
			counter++
			if (counter >= LOOP_COUNTER) {
				reset()
				callback.onLoopDetected()
			}
		} else {
			counter = 0
		}
	}

	private fun checkResolved(webView: WebView) {
		if (passed) return
		if (clearanceChanged()) {
			pass()
			return
		}
		val pageUrl = webView.url ?: return scheduleRecheck(webView)
		val challengePage = pageUrl.isChallengeUrl()
		if (!challengePage) {
			val pageHost =
				pageUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")
					?: return scheduleRecheck(webView)
			if (targetHost != null && pageHost != targetHost) return scheduleRecheck(webView)
		}
		webView.evaluateJavascript(CHALLENGE_PROBE_SCRIPT) { result ->
			runCatching {
				if (passed) return@evaluateJavascript
				if (clearanceChanged()) {
					pass()
					return@evaluateJavascript
				}
				val probe = parseChallengeProbe(result)
				if (probe.hasChallenge || probe.hasTurnstile) sawChallenge = true
				when {
					// A real token in the page means the site's own check already ran
					probe.hasToken -> {
						pass()
					}

					probe.isPending -> {
						cleanProbes = 0
						if (probe.hasTurnstile && !probe.hasToken && turnstileClicks < MAX_TURNSTILE_CLICKS) {
							probe.turnstileCenter?.let { center ->
								turnstileClicks++
								clickAt(webView, center)
							}
						}
						scheduleRecheck(webView)
					}

					// Never treat a challenge page that only looks empty as solved
					challengePage -> {
						cleanProbes = 0
						scheduleRecheck(webView)
					}

					!pageFinished -> {
						cleanProbes = 0
						scheduleRecheck(webView)
					}

					// The challenge was there and is gone now
					sawChallenge -> {
						pass()
					}

					else -> {
						cleanProbes++
						val settled =
							finishedAt > 0 && SystemClock.elapsedRealtime() - finishedAt >= CLEAN_PASS_DELAY
						if (settled && cleanProbes >= CLEAN_PASS_PROBES) {
							pass()
						} else {
							scheduleRecheck(webView)
						}
					}
				}
			}.onFailure {
				it.printStackTraceDebug()
				scheduleRecheck(webView)
			}
		}
	}

	/** Taps the Turnstile checkbox for the user; the WebView is on screen here, so a real tap works. */
	private fun clickAt(
		webView: WebView,
		center: Pair<Int, Int>,
	) {
		val x = center.first.toFloat()
		val y = center.second.toFloat()
		val now = SystemClock.uptimeMillis()
		val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
		val up = MotionEvent.obtain(now, now + CLICK_HOLD, MotionEvent.ACTION_UP, x, y, 0)
		try {
			webView.dispatchTouchEvent(down)
			webView.dispatchTouchEvent(up)
		} finally {
			down.recycle()
			up.recycle()
		}
	}

	private fun scheduleRecheck(webView: WebView) {
		if (passed) return
		lastWebView = webView
		webView.removeCallbacks(recheck)
		webView.postDelayed(recheck, RECHECK_INTERVAL)
	}

	private fun pass() {
		if (passed) return
		passed = true
		lastWebView?.removeCallbacks(recheck)
		callback.onCheckPassed()
	}

	private fun clearanceChanged(): Boolean {
		val clearance = getClearance()
		return clearance != null && clearance != oldClearance
	}

	private fun getClearance() = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
}
