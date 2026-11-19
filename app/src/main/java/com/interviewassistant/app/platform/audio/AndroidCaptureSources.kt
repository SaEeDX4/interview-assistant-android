package com.interviewassistant.app.platform.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import com.interviewassistant.app.services.AppLog
import com.interviewassistant.core.audio.SampleEncoding
import com.interviewassistant.core.audio.SourceFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The platform halves of [CaptureSource]: an [AudioRecord] read loop, once over the microphone
 * routes (D1 primary mode) and once over AudioPlaybackCapture (OD-4 Mode 2). All behavioural
 * decisions (statuses, restarts, silence, framing) live in [CaptureCore]; these classes only
 * negotiate a format, move bytes, and report that they stopped.
 *
 * Format negotiation mirrors WASAPI's "take the device's mix format": the microphone tries
 * 48 kHz PCM16 mono first (the near-universal Android capture format), then the other standard
 * rates; playback capture is 48 kHz PCM16 stereo, the platform's playback-capture format. What
 * was negotiated is reported through [CaptureSource.format] and converted by :core's
 * `PcmConverter`, exactly as the C# converts the WASAPI mix format.
 */
private class AudioRecordSource(
    override val format: SourceFormat,
    override val deviceName: String,
    private val buildRecord: () -> AudioRecord,
    private val onReleased: () -> Unit = {},
) : CaptureSource {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val alive = AtomicBoolean(false)

    override fun start(onData: (ByteArray) -> Unit, onStopped: (Exception?) -> Unit) {
        val rec = try {
            buildRecord()
        } catch (ex: Exception) {
            throw SourceStartException(noDevice = false, cause = ex)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw SourceStartException(noDevice = false)
        }
        record = rec
        alive.set(true)
        try {
            rec.startRecording()
        } catch (ex: Exception) {
            alive.set(false)
            rec.release()
            record = null
            throw SourceStartException(noDevice = false, cause = ex)
        }
        thread = Thread({
            val buffer = ByteArray(readBufferSize(rec, format))
            var failure: Exception? = null
            while (alive.get()) {
                val n = try {
                    rec.read(buffer, 0, buffer.size)
                } catch (ex: Exception) {
                    failure = ex
                    break
                }
                when {
                    n > 0 -> onData(buffer.copyOf(n))
                    n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION -> {
                        failure = IllegalStateException("AudioRecord.read failed ($n)")
                        break
                    }
                    n < 0 -> {
                        failure = IllegalStateException("AudioRecord.read failed ($n)")
                        break
                    }
                    else -> if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                }
            }
            val wasAlive = alive.getAndSet(false)
            if (wasAlive) onStopped(failure) // ended on its own: device loss (the C#'s RecordingStopped)
        }, "AudioCaptureRead").apply { isDaemon = true; start() }
    }

    override fun stop() {
        alive.set(false)
        val rec = record ?: return
        record = null
        try {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
        } catch (ignored: Exception) {
            // Device already gone.
        }
        rec.release()
        onReleased()
        thread = null
    }

    private fun readBufferSize(rec: AudioRecord, format: SourceFormat): Int {
        val min = rec.bufferSizeInFrames * format.bytesPerSample * format.channels
        // ~100 ms of audio per read keeps the converter fed at the frame cadence.
        val hundredMs = format.sampleRate * format.bytesPerSample * format.channels / 10
        return maxOf(min, hundredMs)
    }
}

/**
 * Creates microphone sources over the Android capture routes (D1 primary mode). The
 * MissingPermission suppression is honest: `AudioCaptureService.start` gates every start on
 * the RECORD_AUDIO grant before a source is ever created (DC-172).
 */
@SuppressLint("MissingPermission")
internal class MicrophoneSourceFactory(
    private val audioManager: AudioManager,
    private val routes: () -> List<CaptureRoutes.RouteInfo>,
    private val deviceFor: (CaptureRoutes.RouteKind) -> AudioDeviceInfo?,
    private val defaultDeviceName: () -> String,
) : CaptureSourceFactory {
    override fun create(resolved: CaptureRoutes.Resolved): CaptureSource {
        val kind = (resolved as? CaptureRoutes.Resolved.Route)?.kind // null = follow the system default
        val device = kind?.let { deviceFor(it) }
        if (kind != null && device == null) throw SourceStartException(noDevice = true)
        val name = when {
            kind == null -> defaultDeviceName()
            else -> routes().firstOrNull { it.kind == kind }?.name ?: device?.productName?.toString().orEmpty()
        }
        return buildMicSource(kind, device, name)
    }

    private fun buildMicSource(
        kind: CaptureRoutes.RouteKind?,
        device: AudioDeviceInfo?,
        name: String,
    ): CaptureSource {
        // Negotiate like WASAPI takes the mix format: first rate the device accepts, PCM16 mono.
        var negotiated: Pair<AudioRecord, SourceFormat>? = null
        var lastFailure: Exception? = null
        for (rate in MIC_RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val rec = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
            } catch (ex: Exception) {
                lastFailure = ex
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                negotiated = rec to SourceFormat(rate, 1, SampleEncoding.Pcm16)
                break
            }
            rec?.release()
        }
        val (record, format) = negotiated
            ?: throw SourceStartException(noDevice = false, cause = lastFailure)
        if (device != null) {
            // Route the recording to the chosen device; for Bluetooth/wired routes the
            // communication device is the platform's route switch (API 31+). The default route
            // (device == null) is never forced: the system picks, as the C# follows the default.
            record.preferredDevice = device
            if (kind == CaptureRoutes.RouteKind.Bluetooth || kind == CaptureRoutes.RouteKind.Wired) {
                setCommunicationDevice(device)
            }
        }
        return AudioRecordSource(format, name, buildRecord = { record }, onReleased = { clearCommunicationDevice() })
    }

    private fun setCommunicationDevice(device: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.setCommunicationDevice(device)
            } catch (ex: Exception) {
                AppLog.warn("Could not set communication device: ${ex.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
        }
    }

    private fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (ignored: Exception) {
                // Nothing to clear.
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
    }

    private companion object {
        val MIC_RATES: List<Int> = listOf(48_000, 44_100, 24_000, 16_000)
    }
}

/**
 * Creates the AudioPlaybackCapture source (OD-4 Mode 2). Requires a live [MediaProjection] from
 * the user's consent; without one the factory throws and the service's microphone fallback (the
 * C#-shaped restart path over the default route) is what runs — see [AudioCaptureService].
 */
internal class PlaybackSourceFactory(
    private val projection: () -> MediaProjection?,
    private val deviceName: () -> String,
) : CaptureSourceFactory {
    override fun create(resolved: CaptureRoutes.Resolved): CaptureSource {
        val proj = projection() ?: throw SourceStartException(noDevice = false)
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            // The usages a meeting can play under; an app that forbids capture
            // (ALLOW_CAPTURE_BY_NONE) is simply absent from the mix — silent-by-absence,
            // exactly the property the C#'s loopback has and the silence injector covers.
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .build()
        val format = SourceFormat(48_000, 2, SampleEncoding.Pcm16)
        return AudioRecordSource(
            format,
            deviceName(),
            buildRecord = {
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48_000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(48_000 * 2 * 2 / 10 * 4)
                    .build()
            },
        )
    }
}
