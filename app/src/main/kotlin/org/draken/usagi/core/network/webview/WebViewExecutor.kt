package org.draken.usagi.core.network.webview

import android.content.Context
import android.content.res.Resources
import android.util.AndroidRuntimeException
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.core.exceptions.CloudFlareException
import org.draken.usagi.core.network.CommonHeaders
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.network.proxy.ProxyProvider
import org.draken.usagi.core.parser.MangaParserRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.util.ext.configureForParser
import org.draken.usagi.core.util.ext.printStackTraceDebug
import tsuki.model.MangaSource
import tsuki.util.runCatchingCancellable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class WebViewExecutor
	@Inject
	constructor(
		@ApplicationContext private val context: Context,
		private val proxyProvider: ProxyProvider,
		private val cookieJar: MutableCookieJar,
		private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
	) {
		private var webViewCached: WeakReference<WebView>? = null
		private val mutex = Mutex()
		private val challengeCoordinator = HostChallengeCoordinator()

		val defaultUserAgent: String? by lazy {
			try {
				WebSettings.getDefaultUserAgent(context)
			} catch (e: AndroidRuntimeException) {
				e.printStackTraceDebug()
				// Probably WebView is not available
				null
			}
		}

		suspend fun evaluateJs(
			baseUrl: String?,
			script: String,
		): String? =
			mutex.withLock {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					try {
						if (!baseUrl.isNullOrEmpty()) {
							suspendCoroutine { cont ->
								webView.webViewClient = ContinuationResumeWebViewClient(cont)
								webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
							}
						}
						suspendCoroutine { cont ->
							webView.evaluateJavascript(script) { result ->
								cont.resume(result?.takeUnless { it == "null" })
							}
						}
					} finally {
						webView.reset()
					}
				}
			}

		suspend fun tryResolveCaptcha(
			exception: CloudFlareException,
			timeout: Long,
		): Boolean {
			val host = exception.url.toHttpUrlOrNull()?.host ?: return false
			return challengeCoordinator.resolve(host) {
				resolveCaptcha(exception, timeout)
			}
		}

		private suspend fun resolveCaptcha(
			exception: CloudFlareException,
			timeout: Long,
		): Boolean =
			mutex.withLock {
				runCatchingCancellable {
					withContext(Dispatchers.Main.immediate) {
						val webView = obtainWebView()
						try {
							val userAgent = exception.requestHeaders["User-Agent"] ?: exception.source.getUserAgent()
							userAgent?.let {
								webView.settings.userAgentString = it
							}
							withTimeoutOrNull(timeout) {
								suspendCancellableCoroutine<Unit> { cont ->
									webView.webViewClient =
										CaptchaContinuationClient(
											cookieJar = cookieJar,
											targetUrl = exception.url,
											continuation = cont,
										)
									webView.loadUrl(exception.url, exception.requestHeaders.toMap())
								}
							} != null
						} finally {
							webView.reset()
						}
					}
				}.onFailure { e ->
					exception.addSuppressed(e)
					e.printStackTraceDebug()
				}.getOrDefault(false)
			}

		private suspend fun obtainWebView(): WebView {
			webViewCached?.get()?.let {
				return it
			}
			return withContext(Dispatchers.Main.immediate) {
				webViewCached?.get()?.let {
					return@withContext it
				}
				WebView(context).also {
					it.configureForParser(null)
					it.settings.useWideViewPort = false
					it.settings.loadWithOverviewMode = false
					it.layoutOffscreen()
					webViewCached = WeakReference(it)
					proxyProvider.applyWebViewConfig()
					it.onResume()
					it.resumeTimers()
				}
			}
		}

		private fun MangaSource.getUserAgent(): String? {
			val repository = mangaRepositoryFactoryProvider.get().create(this) as? MangaParserRepository
			return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
		}

		@MainThread
		private fun WebView.reset() {
			stopLoading()
			webViewClient = WebViewClient()
			settings.userAgentString = defaultUserAgent
			loadDataWithBaseURL(null, " ", "text/html", null, null)
			clearHistory()
		}
	}

/**
 * An unattached WebView has no window, so it has no size and the page never lays out. Cloudflare
 * and Turnstile need a real viewport (and the widget needs real coordinates) to pass.
 */
private fun WebView.layoutOffscreen() {
	val metrics = Resources.getSystem().displayMetrics
	val width = metrics.widthPixels
	val height = metrics.heightPixels
	if (width <= 0 || height <= 0) return
	layoutParams = ViewGroup.LayoutParams(width, height)
	measure(
		View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
		View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
	)
	layout(0, 0, width, height)
}
