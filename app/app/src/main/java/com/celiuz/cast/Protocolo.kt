package com.celiuz.cast

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Lo que viaja por el socket. El contrato entero está en PROTOCOLO.md. */
object Protocolo {
    val SALUDO = byteArrayOf('C'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(),
        'S'.code.toByte(), 'T'.code.toByte(), 1)
    const val CABECERA = 14

    const val HOLA = 1
    const val VIDEO_CONFIG = 2
    const val VIDEO = 3
    const val AUDIO_CONFIG = 4
    const val AUDIO = 5
    const val ESTADO = 6
    const val FIN = 7
    const val PEDIR_CLAVE = 16

    const val CLAVE = 1
    const val CONFIG = 2

    /** Nombre del socket local abstracto (adb forward ... localabstract:celiuz-cast). */
    const val SOCKET = "celiuz-cast"

    fun cabecera(tipo: Int, banderas: Int, pts: Long, largo: Int): ByteArray =
        ByteBuffer.allocate(CABECERA)
            .put(tipo.toByte()).put(banderas.toByte()).putLong(pts).putInt(largo)
            .array()

    data class Mensaje(val tipo: Int, val banderas: Int, val pts: Long, val datos: ByteArray)

    /** Lee un paquete entero; lanza IOException si la conexión se corta. */
    fun leer(entrada: InputStream): Mensaje {
        val d = DataInputStream(entrada)
        val cab = ByteArray(CABECERA)
        d.readFully(cab)
        val b = ByteBuffer.wrap(cab)
        val tipo = b.get().toInt() and 0xff
        val banderas = b.get().toInt() and 0xff
        val pts = b.long
        val largo = b.int
        if (largo < 0 || largo > 1 shl 20) throw IOException("paquete imposible: $largo bytes")
        val datos = ByteArray(largo)
        d.readFully(datos)
        return Mensaje(tipo, banderas, pts, datos)
    }

    /** JSON mínimo, sin dependencias (solo textos, números, booleanos y null). */
    fun json(vararg pares: Pair<String, Any?>): ByteArray =
        pares.joinToString(",", "{", "}") { (k, v) -> "${texto(k)}:${valor(v)}" }
            .toByteArray(Charsets.UTF_8)

    private fun valor(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Int, is Long -> v.toString()
        else -> texto(v.toString())
    }

    private fun texto(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
