package org.draken.usagi.core.network.webview

internal data class ChallengeProbe(
	val known: Boolean,
	val hasChallenge: Boolean,
	val hasTurnstile: Boolean,
	val hasToken: Boolean,
	val turnstileCenter: Pair<Int, Int>?,
) {
	/**
	 * An unreadable document counts as pending: a callback that arrives as `null` (the page is
	 * still loading or navigating) must never look like a solved challenge.
	 */
	val isPending: Boolean get() = !known || hasChallenge || (hasTurnstile && !hasToken)
}

internal fun parseChallengeProbe(raw: String?): ChallengeProbe {
	val value =
		raw
			?.trim()
			?.removeSurrounding("\"")
			?.replace("\\\"", "")
			.orEmpty()
	val parts = value.split(';', limit = 2)
	val flags = parts[0].toIntOrNull()
	val center =
		parts
			.getOrNull(1)
			?.split(',')
			?.takeIf { it.size == 2 }
			?.let { (x, y) ->
				val xi = x.trim().toIntOrNull() ?: return@let null
				val yi = y.trim().toIntOrNull() ?: return@let null
				xi to yi
			}
	return ChallengeProbe(
		known = flags != null,
		hasChallenge = flags != null && flags and FLAG_CHALLENGE != 0,
		hasTurnstile = flags != null && flags and FLAG_TURNSTILE != 0,
		hasToken = flags != null && flags and FLAG_TOKEN != 0,
		turnstileCenter = center,
	)
}

/**
 * Reports whether the current document still shows a Cloudflare challenge or Turnstile widget,
 * whether the widget already produced a token, and where the widget sits on screen (in device
 * pixels) so the host can tap it when the check needs an interaction.
 */
internal const val CHALLENGE_PROBE_SCRIPT: String =
	"""
	(function () {
		try {
			var doc = document;
			var body = doc.body || doc.documentElement;
			var text = ((doc.title || '') + ' ' + (body ? (body.innerText || '') : '')).toLowerCase();
			var tokenInput = doc.querySelector('input[name="cf-turnstile-response"]');
			var widget = doc.querySelector('div.cf-turnstile, div#turnstile-wrapper, iframe[src*="challenges.cloudflare.com"]');
			var challenge = !!(
				doc.querySelector('form#challenge-form, #challenge-stage, #cf-wrapper, #challenge-error-title, #challenge-running, #challenge-body-text, .cf-browser-verification, iframe[src*="/cdn-cgi/challenge-platform/"]') ||
				text.indexOf('just a moment') >= 0 ||
				text.indexOf('verify you are human') >= 0 ||
				text.indexOf('attention required') >= 0 ||
				text.indexOf('needs to review the security') >= 0
			);
			var center = null;
			if (widget) {
				var rect = widget.getBoundingClientRect();
				if (rect.top < 0 || rect.bottom > window.innerHeight || rect.left < 0 || rect.right > window.innerWidth) {
					widget.scrollIntoView({ block: 'center', inline: 'center' });
					rect = widget.getBoundingClientRect();
				}
				if (rect.width > 4 && rect.height > 4) {
					var ratio = window.devicePixelRatio || 1;
					center = [
						Math.round((rect.left + Math.min(rect.width / 2, 32)) * ratio),
						Math.round((rect.top + Math.min(rect.height / 2, 32)) * ratio)
					];
				}
			}
			return ((challenge ? 1 : 0) | (widget ? 2 : 0) | (tokenInput && tokenInput.value ? 4 : 0)) +
				(center ? ';' + center[0] + ',' + center[1] : '');
		} catch (e) {
			// -1 sets every flag, so the host keeps waiting instead of declaring success
			return '-1';
		}
	})()
	"""

internal const val FLAG_CHALLENGE = 1
internal const val FLAG_TURNSTILE = 2
internal const val FLAG_TOKEN = 4
