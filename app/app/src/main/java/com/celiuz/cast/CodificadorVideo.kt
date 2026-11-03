package com.celiuz.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * El codificador H.264 del celular (por hardware). Recibe la imagen en su
 * [superficie] y entrega cada imagen ya comprimida a [salida].
 *
 * Se crea con [crear], que prueba tamaños de mayor a menor hasta que el
 * codificador acepte uno (ver Tamano.escalones).
 */
class CodificadorVideo private constructor(
    private val codec: MediaCodec,
    val ancho: Int,
    val alto: Int,
    val superficie: Surface,
    private val salida: (datos: ByteArray, banderas: Int, pts: Long) -> Unit,
) {
    @Volatile private var vivo = true
    private val hilo = Thread({ bucle() }, "celiuz-cast-codificador").apply { start() }

    private fun bucle() {
        val info = MediaCodec.BufferInfo()
        try {
            while (vivo) {
                val i = codec.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val buf = codec.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val datos = ByteArray(info.size)
                    buf.position(info.offset); buf.get(datos)
                    var banderas = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) banderas = banderas or Protocolo.CLAVE
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) banderas = banderas or Protocolo.CONFIG
                    salida(datos, banderas, info.presentationTimeUs)
                }
                codec.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: IllegalStateException) {
            if (vivo) Log.w(TAG, "el codificador se paró: ${e.message}")
        }
    }

    fun pedirClave() = parametro(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
    fun cambiarBitrate(bps: Int) = parametro(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)

    private fun parametro(clave: String, valor: Int) {
        if (!vivo) return
        try { codec.setParameters(Bundle().apply { putInt(clave, valor) }) }
        catch (e: IllegalStateException) { Log.w(TAG, "no se pudo cambiar $clave: ${e.message}") }
    }

    fun detener() {
        if (!vivo) return
        vivo = false
        try { codec.stop() } catch (_: IllegalStateException) {}
        hilo.join(500)
        codec.release()
        superficie.release()
    }

    companion object {
        const val TAG = "CeliuzCast"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Un codificador para un contenido de [ancho]x[alto], o null si el
         * celular no acepta ningún tamaño. Prueba primero con los ajustes de
         * poco retraso y, si el codificador los rechaza, sin ellos.
         */
        fun crear(ancho: Int, alto: Int, calidad: Calidad, bitrate: Int,
                  salida: (ByteArray, Int, Long) -> Unit): CodificadorVideo? {
            for (lado in Tamano.escalones(calidad.ladoMax)) {
                val (w, h) = Tamano.para(ancho, alto, lado)
                for (extras in listOf(true, false)) {
                    val codec = MediaCodec.createEncoderByType(MIME)
                    try {
                        val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
                        if (caps != null && !caps.isSizeSupported(w, h)) {
                            Log.i(TAG, "el codificador no admite ${w}x$h")
                            codec.release(); break
                        }
                        codec.configure(formato(w, h, calidad, bitrate, extras), null, null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE)
                        val sup = codec.createInputSurface()
                        codec.start()
                        Log.i(TAG, "codificador ${codec.name} a ${w}x$h, ${bitrate / 1000} kbps, extras=$extras")
                        return CodificadorVideo(codec, w, h, sup, salida)
                    } catch (e: Exception) {
                        Log.w(TAG, "el codificador no acepta ${w}x$h (extras=$extras): $e")
                        codec.release()
                    }
                }
            }
            return null
        }

        private fun formato(w: Int, h: Int, calidad: Calidad, bitrate: Int, extras: Boolean) =
            MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, calidad.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
                // Con la pantalla quieta, repetir la última imagen cada 100 ms:
                // la PC distingue así «quieta» de «conexión muerta».
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                if (extras) {
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, calidad.fps.toFloat())
                    setInteger(MediaFormat.KEY_PRIORITY, 0)                 // tiempo real
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)             // las B atrasan
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                    if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
    }
}
