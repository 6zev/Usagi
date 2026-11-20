package org.draken.usagi.core.network.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class HostChallengeCoordinator {
	private val mutex = Mutex()
	private val inFlight = mutableMapOf<String, CompletableDeferred<Boolean>>()

	suspend fun resolve(
		host: String,
		resolver: suspend () -> Boolean,
	): Boolean {
		val result = CompletableDeferred<Boolean>()
		val active =
			mutex.withLock {
				inFlight[host]?.let { return@withLock it }
				inFlight[host] = result
				result
			}
		if (active !== result) {
			return active.await()
		}
		try {
			return resolver().also(result::complete)
		} catch (e: Throwable) {
			result.complete(false)
			throw e
		} finally {
			mutex.withLock {
				if (inFlight[host] === result) {
					inFlight.remove(host)
				}
			}
		}
	}
}
