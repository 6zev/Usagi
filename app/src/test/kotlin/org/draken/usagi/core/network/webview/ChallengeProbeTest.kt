package org.draken.usagi.core.network.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeProbeTest {
	@Test
	fun `parses an idle page`() {
		val probe = parseChallengeProbe("\"0\"")
		assertTrue(probe.known)
		assertFalse(probe.hasChallenge)
		assertFalse(probe.hasTurnstile)
		assertFalse(probe.hasToken)
		assertFalse(probe.isPending)
		assertNull(probe.turnstileCenter)
	}

	@Test
	fun `parses a challenge with a turnstile widget and its center`() {
		val probe = parseChallengeProbe("\"3;120,340\"")
		assertTrue(probe.known)
		assertTrue(probe.hasChallenge)
		assertTrue(probe.hasTurnstile)
		assertFalse(probe.hasToken)
		assertTrue(probe.isPending)
		assertEquals(120 to 340, probe.turnstileCenter)
	}

	@Test
	fun `a solved standalone turnstile is no longer pending`() {
		val probe = parseChallengeProbe("\"6\"")
		assertFalse(probe.hasChallenge)
		assertTrue(probe.hasTurnstile)
		assertTrue(probe.hasToken)
		assertFalse(probe.isPending)
	}

	@Test
	fun `a challenge with a token still waits for the page to move on`() {
		val probe = parseChallengeProbe("\"5\"")
		assertTrue(probe.hasChallenge)
		assertTrue(probe.hasToken)
		assertTrue(probe.isPending)
	}

	@Test
	fun `a callback without a document stays pending`() {
		val probe = parseChallengeProbe(null)
		assertFalse(probe.known)
		assertFalse(probe.hasChallenge)
		assertTrue(probe.isPending)
		assertNull(probe.turnstileCenter)
	}

	@Test
	fun `a script failure stays pending`() {
		val probe = parseChallengeProbe("\"-1\"")
		assertTrue(probe.known)
		assertTrue(probe.hasChallenge)
		assertTrue(probe.isPending)
	}

	@Test
	fun `malformed center keeps the widget state`() {
		val probe = parseChallengeProbe("\"2;oops\"")
		assertTrue(probe.hasTurnstile)
		assertNull(probe.turnstileCenter)
	}
}
