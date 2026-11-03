package com.celiuz.cast

/** Un paquete listo para salir (ver PROTOCOLO.md). */
class Paquete(val tipo: Int, val banderas: Int, val pts: Long, val datos: ByteArray) {
    val esImagen get() = tipo == Protocolo.VIDEO && (banderas and Protocolo.CONFIG) == 0
    val esClave get() = esImagen && (banderas and Protocolo.CLAVE) != 0
}

/**
 * La cola de salida hacia la PC, y la que decide qué tirar cuando la conexión
 * no da para más. Mejor perder unas imágenes que acumular retraso: en un
 * stream, ir 2 s por detrás es peor que un salto.
 *
 * - Imágenes: si lo que espera abarca más de [limiteVideoUs], se tiran todas y
 *   no entra ninguna hasta la siguiente clave (sin ella la PC pinta basura).
 *   [meterVideo] devuelve true para que quien la llame baje el bitrate y pida
 *   la clave.
 * - Sonido: nunca lo tira un atasco de vídeo; si él mismo pasa de
 *   [limiteAudio] paquetes, se tira el más viejo.
 *
 * Sin nada de Android dentro: se prueba en la PC (ColaTest).
 */
class Cola(
    private val limiteVideoUs: Long = 250_000,
    private val limiteAudio: Int = 20,
) {
    private val lista = ArrayDeque<Paquete>()
    private val cerrojo = Object()
    private var cerrada = false

    /** Hasta que llegue una clave no entra ninguna imagen. Empieza esperando. */
    var esperandoClave = true
        get() = synchronized(cerrojo) { field }
        private set

    fun meter(p: Paquete) = synchronized(cerrojo) {
        if (cerrada) return
        lista.addLast(p)
        cerrojo.notifyAll()
    }

    fun meterAudio(p: Paquete) = synchronized(cerrojo) {
        if (cerrada) return
        if (lista.count { it.tipo == Protocolo.AUDIO } >= limiteAudio) {
            val i = lista.indexOfFirst { it.tipo == Protocolo.AUDIO }
            if (i >= 0) lista.removeAt(i)
        }
        lista.addLast(p)
        cerrojo.notifyAll()
    }

    /** true = hubo atasco y se tiró lo que esperaba. */
    fun meterVideo(p: Paquete): Boolean = synchronized(cerrojo) {
        if (cerrada) return false
        if (!p.esImagen) {           // la configuración (SPS/PPS) pasa siempre
            lista.addLast(p); cerrojo.notifyAll(); return false
        }
        if (esperandoClave && !p.esClave) return false
        val masVieja = lista.firstOrNull { it.esImagen }
        val atasco = masVieja != null && p.pts - masVieja.pts > limiteVideoUs
        if (atasco) {
            lista.removeAll { it.esImagen }
            esperandoClave = !p.esClave
            if (p.esClave) lista.addLast(p)
        } else {
            if (p.esClave) esperandoClave = false
            lista.addLast(p)
        }
        cerrojo.notifyAll()
        atasco
    }

    /** Vuelve a esperar una clave (cliente nuevo, decodificador rehecho). */
    fun esperarClave() = synchronized(cerrojo) {
        lista.removeAll { it.esImagen }
        esperandoClave = true
    }

    /** El siguiente paquete, o null si en [esperaMs] no llegó nada o se cerró. */
    fun sacar(esperaMs: Long): Paquete? = synchronized(cerrojo) {
        if (lista.isEmpty() && !cerrada) cerrojo.wait(esperaMs)
        lista.removeFirstOrNull()
    }

    fun cerrar() = synchronized(cerrojo) {
        cerrada = true
        lista.clear()
        cerrojo.notifyAll()
    }

    val tamano: Int get() = synchronized(cerrojo) { lista.size }
}
