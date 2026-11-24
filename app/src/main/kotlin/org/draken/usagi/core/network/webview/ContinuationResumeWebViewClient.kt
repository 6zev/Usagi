package org.draken.usagi.core.network.webview

import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class ContinuationResumeWebViewClient(
	protected val continuation: Continuation<Unit>,
) : WebViewClient() {
	override fun onPageFinished(
		view: WebView?,
		url: String?,
	) {
		resumeContinuation(view)
	}

	protected fun isContinuationActive(): Boolean = continuation !is CancellableContinuation || continuation.isActive

	protected open fun resumeContinuation(view: WebView?) {
		if (isContinuationActive()) {
			view?.webViewClient = WebViewClient() // reset to default
			continuation.resume(Unit)
		}
	}

	protected fun resumeContinuationWithException(
		view: WebView?,
		error: Throwable,
	) {
		if (isContinuationActive()) {
			view?.webViewClient = WebViewClient() // reset to default
			continuation.resumeWithException(error)
		}
	}
}
