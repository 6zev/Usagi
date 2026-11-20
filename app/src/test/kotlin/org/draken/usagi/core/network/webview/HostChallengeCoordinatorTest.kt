package org.draken.usagi.core.network.webview

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostChallengeCoordinatorTest {
	@Test
	fun `coalesces concurrent challenges for the same host`() =
		runTest {
			val coordinator = HostChallengeCoordinator()
			var executions = 0

			val results =
				coroutineScope {
					List(8) {
						async {
							coordinator.resolve("example.org") {
								executions++
								delay(50)
								true
							}
						}
					}.awaitAll()
				}

			assertEquals(1, executions)
			assertTrue(results.all { it })
		}

	@Test
	fun `allows independent challenges for separate hosts`() =
		runTest {
			val coordinator = HostChallengeCoordinator()
			var executions = 0

			val results =
				coroutineScope {
					listOf("one.example", "two.example")
						.map { host ->
							async {
								coordinator.resolve(host) {
									executions++
									true
								}
							}
						}.awaitAll()
				}

			assertEquals(2, executions)
			assertTrue(results.all { it })
		}

	@Test
	fun `removes completed host so later challenges can run`() =
		runTest {
			val coordinator = HostChallengeCoordinator()
			var executions = 0

			assertFalse(
				coordinator.resolve("example.org") {
					executions++
					false
				},
			)
			assertTrue(
				coordinator.resolve("example.org") {
					executions++
					true
				},
			)

			assertEquals(2, executions)
		}
}
