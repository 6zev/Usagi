package org.draken.usagi.core.network.webview

import android.graphics.Bitmap
import android.webkit.WebView
import org.draken.usagi.core.network.cookies.MutableCookieJar
import tsuki.network.CloudFlareHelper
import kotlin.coroutines.Continuation

class CaptchaContinuationClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	continuation: Continuation<Unit>,
) : ContinuationResumeWebViewClient(continuation) {
	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
	private var pageFinished = false

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
		super.onPageStarted(view, url, favicon)
		pageFinished = false
		checkResolved(view)
	}

	override fun onPageCommitVisible(
		view: WebView,
		url: String,
	) {
		super.onPageCommitVisible(view, url)
		checkResolved(view)
	}

	private fun checkResolved(view: WebView?) {
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (clearance != null && clearance != oldClearance) {
			resumeContinuation(view)
			return
		}
		if (pageFinished && view?.url?.isChallengeUrl() == false) {
			resumeContinuation(view)
		}
	}
}

internal fun String.isChallengeUrl(): Boolean {
	val value = lowercase()
	return value.contains("/cdn-cgi/challenge-platform/") ||
		value.contains("/cdn-cgi/l/chk_captcha") ||
		value.contains("challenges.cloudflare.com")
}
