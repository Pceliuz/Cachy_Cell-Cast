package com.celiuz.cast

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log

/**
 * El sonido que suena en el celular (Android 10 en adelante), en PCM de
 * 48 kHz estéreo, en trozos de 10 ms.
 *
 * Con [uid] > 0 solo el de esa app. Sin él, el de todas las apps de música,
 * juegos y vídeo. Los avisos y las llamadas no entran nunca: Android no deja
 * capturar esos usos.
 *
 * Una app puede prohibir que se capture su sonido (lo deciden sus
 * desarrolladores); entonces llega silencio y no hay nada que hacer desde aquí.
 */
class CapturaAudio @SuppressLint("MissingPermission") constructor(
    proyeccion: MediaProjection,
    uid: Int,
    private val salida: (datos: ByteArray, pts: Long) -> Unit,
) {
    private val grabacion: AudioRecord
    @Volatile private var vivo = true
    private val hilo: Thread

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(proyeccion).apply {
            addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            addMatchingUsage(AudioAttributes.USAGE_GAME)
            addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            if (uid > 0) addMatchingUid(uid)
        }.build()
        val formato = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(FRECUENCIA)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minimo = AudioRecord.getMinBufferSize(FRECUENCIA, AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        grabacion = AudioRecord.Builder()
            .setAudioFormat(formato)
            .setBufferSizeInBytes(maxOf(minimo, TROZO * 8))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        grabacion.startRecording()
        hilo = Thread({ bucle() }, "celiuz-cast-audio").apply { start() }
    }

    private fun bucle() {
        while (vivo) {
            val trozo = ByteArray(TROZO)
            var leido = 0
            while (leido < TROZO && vivo) {
                val n = grabacion.read(trozo, leido, TROZO - leido)
                if (n <= 0) {
                    if (n < 0) { Log.w(TAG, "AudioRecord.read: $n"); Thread.sleep(20) }
                    break
                }
                leido += n
            }
            if (leido == TROZO) salida(trozo, System.nanoTime() / 1000)
        }
    }

    fun detener() {
        vivo = false
        try { grabacion.stop() } catch (_: IllegalStateException) {}
        hilo.join(500)
        grabacion.release()
    }

    companion object {
        const val TAG = "CeliuzCast"
        const val FRECUENCIA = 48_000
        const val CANALES = 2
        /** 10 ms de 48 kHz estéreo en 16 bits. */
        const val TROZO = FRECUENCIA / 100 * CANALES * 2
    }
}
