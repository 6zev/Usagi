package org.draken.usagi.core.exceptions

import okhttp3.Headers
import okio.IOException
import tsuki.model.MangaSource

abstract class CloudFlareException(
	message: String,
	val state: Int,
) : IOException(message) {
	abstract val url: String

	abstract val source: MangaSource

	open val requestHeaders: Headers = Headers.headersOf()
}
