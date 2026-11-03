package com.celiuz.cast

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException

/**
 * Espera a la PC en el socket local `celiuz-cast` y le manda lo que salga de
 * la [Cola]. Un cliente a la vez.
 *
 * Solo acepta a adb (uid 2000) o a root: cualquier app del celular puede
 * conectarse a un socket local, y sin esta comprobación podría ver la
 * pantalla. Por adb solo llega quien tiene la depuración USB autorizada.
 */
class Servidor(
    /** Cliente nuevo: hay que mandarle HOLA, las configuraciones y pedir una clave. */
    private val alConectar: (Cliente) -> Unit,
    /** La PC pidió una imagen clave. */
    private val alPedirClave: () -> Unit,
) {
    private val socket = LocalServerSocket(Protocolo.SOCKET)
    @Volatile private var vivo = true
    @Volatile var cliente: Cliente? = null
        private set

    val conectado: Boolean get() = cliente?.vivo == true

    private val hilo = Thread({ aceptar() }, "celiuz-cast-servidor").apply { start() }

    private fun aceptar() {
        while (vivo) {
            val s = try { socket.accept() } catch (e: IOException) { if (vivo) continue else break }
            if (!vivo) { s.close(); break }
            val uid = try { s.peerCredentials.uid } catch (e: IOException) { -1 }
            if (uid != 2000 && uid != 0) {
                Log.w(TAG, "conexión rechazada de uid $uid (solo adb)")
                s.close(); continue
            }
            cliente?.cerrar()
            val c = Cliente(s)
            cliente = c
            c.empezar()
            alConectar(c)
        }
    }

    fun detener() {
        vivo = false
        cliente?.cerrar()
        // accept() no se despierta al cerrar el socket en Android: se le llama.
        try { LocalSocket().use { it.connect(LocalSocketAddress(Protocolo.SOCKET)) } } catch (_: IOException) {}
        try { socket.close() } catch (_: IOException) {}
        hilo.join(1000)
    }

    inner class Cliente(private val s: LocalSocket) {
        val cola = Cola()
        @Volatile var vivo = true
            private set

        fun empezar() {
            Thread({ escribir() }, "celiuz-cast-envio").start()
            Thread({ leer() }, "celiuz-cast-lectura").start()
        }

        private fun escribir() {
            try {
                val salida = BufferedOutputStream(s.outputStream, 1 shl 16)
                salida.write(Protocolo.SALUDO)
                salida.flush()
                while (vivo) {
                    val p = cola.sacar(500) ?: continue
                    salida.write(Protocolo.cabecera(p.tipo, p.banderas, p.pts, p.datos.size))
                    salida.write(p.datos)
                    if (cola.tamano == 0) salida.flush()
                }
            } catch (e: IOException) {
                Log.i(TAG, "la PC se desconectó: ${e.message}")
            } finally { cerrar() }
        }

        private fun leer() {
            try {
                while (vivo) {
                    val m = Protocolo.leer(s.inputStream)
                    if (m.tipo == Protocolo.PEDIR_CLAVE) {
                        cola.esperarClave()
                        alPedirClave()
                    }
                }
            } catch (_: IOException) {
            } finally { cerrar() }
        }

        fun cerrar() {
            if (!vivo) return
            vivo = false
            cola.cerrar()
            try { s.shutdownInput() } catch (_: IOException) {}
            try { s.close() } catch (_: IOException) {}
        }
    }

    companion object { const val TAG = "CeliuzCast" }
}
