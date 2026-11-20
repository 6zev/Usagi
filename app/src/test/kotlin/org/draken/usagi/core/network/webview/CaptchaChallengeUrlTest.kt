package org.draken.usagi.core.network.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaChallengeUrlTest {
	@Test
	fun `recognizes Cloudflare challenge platform urls`() {
		assertTrue("https://example.org/cdn-cgi/challenge-platform/h/g/orchestrate/jsch/v1".isChallengeUrl())
	}

	@Test
	fun `recognizes legacy Cloudflare captcha urls`() {
		assertTrue("https://example.org/cdn-cgi/l/chk_captcha?id=123".isChallengeUrl())
	}

	@Test
	fun `recognizes Turnstile challenge host`() {
		assertTrue("https://challenges.cloudflare.com/turnstile/v0/api.js".isChallengeUrl())
	}

	@Test
	fun `does not classify regular source pages as challenge urls`() {
		assertFalse("https://example.org/manga/one-piece".isChallengeUrl())
	}
}
