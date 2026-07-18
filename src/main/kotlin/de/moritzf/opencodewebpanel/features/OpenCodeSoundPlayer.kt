package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

/**
 * Plays bundled OpenCode notification sounds (WAV copies of the SPA's AAC assets) on a
 * background thread. JetBrains' JCEF ships without proprietary codecs, so OpenCode's own
 * `.aac` cues never decode in the embedded page; playing WAV via Java Sound sidesteps that.
 */
internal object OpenCodeSoundPlayer {
    private const val DEBOUNCE_MILLIS = 400L
    private const val PLAY_TIMEOUT_MILLIS = 5_000L

    private val lastPlayedAtMillis = ConcurrentHashMap<String, Long>()

    fun playById(id: String?) {
        val soundId = id?.trim().orEmpty()
        if (soundId.isEmpty() || soundId !in OpenCodeSoundSettings.KNOWN_SOUND_IDS) return
        val now = System.currentTimeMillis()
        val previous = lastPlayedAtMillis.put(soundId, now)
        if (previous != null && now - previous < DEBOUNCE_MILLIS) return
        AppExecutorUtil.getAppExecutorService().execute { play(soundId) }
    }

    private fun play(soundId: String) {
        val resource = "/sounds/$soundId.wav"
        val input = OpenCodeSoundPlayer::class.java.getResourceAsStream(resource) ?: run {
            thisLogger().warn("OpenCode sound resource missing: $resource")
            return
        }
        try {
            AudioSystem.getAudioInputStream(input.buffered()).use { audio ->
                val clip = AudioSystem.getClip()
                clip.open(audio)
                val finished = CountDownLatch(1)
                clip.addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP || event.type == LineEvent.Type.CLOSE) {
                        finished.countDown()
                    }
                }
                clip.start()
                if (!finished.await(PLAY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) clip.stop()
                clip.close()
            }
        } catch (error: Exception) {
            thisLogger().warn("Failed to play OpenCode sound $soundId", error)
        } finally {
            runCatching { input.close() }
        }
    }
}
