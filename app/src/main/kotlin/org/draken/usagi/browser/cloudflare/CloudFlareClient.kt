package org.draken.usagi.browser.cloudflare

import android.graphics.Bitmap
import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.browser.BrowserClient
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.network.webview.CHALLENGE_PROBE_SCRIPT
import org.draken.usagi.core.network.webview.adblock.AdBlock
import org.draken.usagi.core.network.webview.isChallengeUrl
import org.draken.usagi.core.network.webview.parseChallengeProbe
import tsuki.network.CloudFlareHelper

private const val LOOP_COUNTER = 5
private const val RECHECK_INTERVAL = 800L

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
		callback.onPageLoaded()
		checkResolved(webView)
	}

	fun reset() {
		counter = 0
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
		val pageUrl = webView.url ?: return
		if (pageUrl.isChallengeUrl()) return scheduleRecheck(webView)
		val pageHost = pageUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return scheduleRecheck(webView)
		if (targetHost != null && pageHost != targetHost) return scheduleRecheck(webView)
		webView.evaluateJavascript(CHALLENGE_PROBE_SCRIPT) { result ->
			if (passed) return@evaluateJavascript
			if (clearanceChanged()) {
				pass()
				return@evaluateJavascript
			}
			if (parseChallengeProbe(result).isPending) {
				scheduleRecheck(webView)
			} else {
				pass()
			}
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
