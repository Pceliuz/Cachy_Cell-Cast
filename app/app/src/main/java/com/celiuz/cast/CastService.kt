package com.celiuz.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Display

/**
 * La transmisión en marcha: la proyección de Android, el codificador, el
 * sonido y el servidor para la PC. Vive como servicio en primer plano (con su
 * notificación), que es lo que Android exige para capturar la pantalla.
 *
 * Sigue viva aunque la PC se desconecte: cuando la PC vuelve (un cable que
 * parpadea), se le manda todo de nuevo sin tener que volver a pedir permiso.
 */
class CastService : Service() {

    private lateinit var hilo: HandlerThread
    private lateinit var manejador: Handler
    private var proyeccion: MediaProjection? = null
    private var pantalla: VirtualDisplay? = null
    @Volatile private var codificador: CodificadorVideo? = null
    private var audio: CapturaAudio? = null
    private var servidor: Servidor? = null
    private var displayManager: DisplayManager? = null

    private lateinit var calidad: Calidad
    private var bitrate = 0
    private var ultimoAtasco = 0L
    private var uidApp = -1
    private var nombreApp: String? = null
    private var modo = "pantalla"
    private var contenido = Point(0, 0)
    private var noMolestarPrevio: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER || intent == null) { detener(); return START_NOT_STICKY }
        if (proyeccion != null) return START_NOT_STICKY

        hilo = HandlerThread("celiuz-cast").apply { start() }
        manejador = Handler(hilo.looper)
        calidad = Calidad.de(intent.getStringExtra(EXTRA_CALIDAD))
        bitrate = calidad.bitrate
        uidApp = intent.getIntExtra(EXTRA_UID, -1)
        nombreApp = intent.getStringExtra(EXTRA_NOMBRE_APP)
        modo = intent.getStringExtra(EXTRA_MODO) ?: "pantalla"
        val conAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        // Android 14: el servicio en primer plano ANTES de pedir la proyección.
        var tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // El tipo «micrófono» no existe en Android 10: allí basta con el de proyección.
        if (conAudio && Build.VERSION.SDK_INT >= 30) tipos = tipos or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIFICACION, notificacion("Esperando a la PC…"), tipos)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        val datos: Intent? = intent.getParcelableExtra(EXTRA_DATOS)
        val p = datos?.let { mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODIGO, 0), it) }
        if (p == null) { detener(); return START_NOT_STICKY }
        proyeccion = p
        p.registerCallback(Avisos(), manejador)

        contenido = tamanoPantalla()
        if (!empezarVideo(contenido.x, contenido.y)) {
            avisar("Tu celular no pudo codificar el vídeo a ningún tamaño.")
            detener(); return START_NOT_STICKY
        }
        if (conAudio) {
            try { audio = CapturaAudio(p, uidApp) { d, pts -> enviarAudio(d, pts) } }
            catch (e: Exception) { Log.w(TAG, "sin sonido: $e") }
        }
        displayManager = getSystemService(DisplayManager::class.java).also {
            it.registerDisplayListener(vigiaPantalla, manejador)
        }
        if (intent.getBooleanExtra(EXTRA_NO_MOLESTAR, false)) ponerNoMolestar()
        // El servidor, lo último: una PC que se conecte antes de que haya
        // codificador se quedaría sin la configuración del vídeo.
        servidor = Servidor(alConectar = { manejador.post { alConectar(it) } },
                            alPedirClave = { codificador?.pedirClave() })
        activo = true
        manejador.postDelayed(::revisarBitrate, 5000)
        manejador.post(::vigilarPc)
        return START_NOT_STICKY
    }

    // --- Vídeo ------------------------------------------------------------

    /** Crea el codificador para un contenido de ese tamaño y engancha la pantalla virtual. */
    private fun empezarVideo(ancho: Int, alto: Int): Boolean {
        configVideo = null   // la del codificador viejo ya no vale
        val nuevo = CodificadorVideo.crear(ancho, alto, calidad, bitrate) { d, b, pts -> enviarVideo(d, b, pts) }
            ?: return false
        val viejo = codificador
        codificador = nuevo
        val dpi = resources.displayMetrics.densityDpi
        val vd = pantalla
        if (vd == null) {
            // Android 14 solo deja crear UNA pantalla virtual por proyección:
            // al girar se le cambia el tamaño y la superficie, no se rehace.
            pantalla = proyeccion!!.createVirtualDisplay("celiuz-cast", nuevo.ancho, nuevo.alto, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, nuevo.superficie, null, manejador)
        } else {
            vd.resize(nuevo.ancho, nuevo.alto, dpi)
            vd.surface = nuevo.superficie
        }
        viejo?.detener()
        servidor?.cliente?.let { mandarConfigVideo(it) }
        return true
    }

    private fun redimensionar(ancho: Int, alto: Int) {
        if (ancho <= 0 || alto <= 0 || (ancho == contenido.x && alto == contenido.y)) return
        Log.i(TAG, "el contenido pasa a ${ancho}x$alto")
        contenido = Point(ancho, alto)
        if (!empezarVideo(ancho, alto)) avisar("No se pudo seguir el cambio de tamaño.")
    }

    @Suppress("DEPRECATION")
    private fun tamanoPantalla(): Point {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return Point().also { d.getRealSize(it) }
    }

    /** Android 14+ avisa del tamaño de lo compartido; si lo hace, manda él. */
    @Volatile private var sigueApp = false

    private val vigiaPantalla = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) {
            // En «una sola app» manda el tamaño de la app (onCapturedContentResize).
            if (id == Display.DEFAULT_DISPLAY && !sigueApp) {
                val t = tamanoPantalla(); redimensionar(t.x, t.y)
            }
        }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
    }

    private fun enviarVideo(datos: ByteArray, banderas: Int, pts: Long) {
        if (banderas and Protocolo.CONFIG != 0) configVideo = datos
        val c = servidor?.cliente ?: return
        val atasco = c.cola.meterVideo(Paquete(Protocolo.VIDEO, banderas, pts, datos))
        if (atasco) manejador.post { alAtascarse() }
        else if (c.cola.esperandoClave && banderas and Protocolo.CONFIG == 0) {
            val ahora = System.currentTimeMillis()
            if (ahora - ultimaPeticionClave > 500) { ultimaPeticionClave = ahora; codificador?.pedirClave() }
        }
    }

    @Volatile private var ultimaPeticionClave = 0L

    @Volatile private var configVideo: ByteArray? = null

    /** La red no da para más: menos bitrate y una clave para seguir limpio. */
    private fun alAtascarse() {
        ultimoAtasco = System.currentTimeMillis()
        bitrate = maxOf(calidad.bitrate / 4, (bitrate * 0.7).toInt())
        codificador?.cambiarBitrate(bitrate)
        codificador?.pedirClave()
        Log.i(TAG, "atasco: bitrate a ${bitrate / 1000} kbps")
        enviarEstado()
    }

    /** Cada segundo: ¿sigue la PC ahí? Para la notificación y la pantalla de la app. */
    private fun vigilarPc() {
        if (proyeccion == null) return
        val ahora = servidor?.conectado == true
        if (ahora != pcConectada) { pcConectada = ahora; actualizarNotificacion() }
        manejador.postDelayed(::vigilarPc, 1000)
    }

    private fun revisarBitrate() {
        if (proyeccion == null) return
        if (bitrate < calidad.bitrate && System.currentTimeMillis() - ultimoAtasco > 5000) {
            bitrate = minOf(calidad.bitrate, (bitrate * 1.15).toInt())
            codificador?.cambiarBitrate(bitrate)
            enviarEstado()
        }
        manejador.postDelayed(::revisarBitrate, 5000)
    }

    // --- Sonido y estado --------------------------------------------------

    private fun enviarAudio(datos: ByteArray, pts: Long) {
        servidor?.cliente?.cola?.meterAudio(Paquete(Protocolo.AUDIO, 0, pts, datos))
    }

    private var visible = true
    private var aviso: String? = null

    private fun enviarEstado() {
        servidor?.cliente?.cola?.meter(Paquete(Protocolo.ESTADO, 0, 0,
            Protocolo.json("visible" to visible, "bitrate" to bitrate, "aviso" to aviso)))
    }

    private fun avisar(texto: String) {
        aviso = texto
        Log.w(TAG, texto)
        enviarEstado()
    }

    // --- La PC ------------------------------------------------------------

    private fun alConectar(c: Servidor.Cliente) {
        c.cola.meter(Paquete(Protocolo.HOLA, 0, 0, Protocolo.json(
            "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT,
            "modelo" to modelo(), "modo" to modo, "app" to nombreApp, "audio" to (audio != null))))
        mandarConfigVideo(c)
        if (audio != null) c.cola.meter(Paquete(Protocolo.AUDIO_CONFIG, 0, 0, Protocolo.json(
            "frecuencia" to CapturaAudio.FRECUENCIA, "canales" to CapturaAudio.CANALES, "formato" to "s16le")))
        enviarEstado()
    }

    private fun mandarConfigVideo(c: Servidor.Cliente) {
        val cod = codificador ?: return
        c.cola.esperarClave()
        c.cola.meter(Paquete(Protocolo.VIDEO_CONFIG, 0, 0, Protocolo.json(
            "ancho" to cod.ancho, "alto" to cod.alto, "codec" to "h264")))
        // Si el codificador ya había sacado su configuración, va delante de la clave.
        configVideo?.let { if (cod === codificador) c.cola.meterVideo(Paquete(Protocolo.VIDEO, Protocolo.CONFIG, 0, it)) }
        cod.pedirClave()
    }

    private fun modelo(): String {
        val nombre = android.provider.Settings.Global.getString(contentResolver, "device_name")
        return if (!nombre.isNullOrBlank() && nombre.trim() != "[ ]") nombre else "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private inner class Avisos : MediaProjection.Callback() {
        override fun onStop() { manejador.post { detener() } }
        override fun onCapturedContentResize(width: Int, height: Int) {
            sigueApp = true
            redimensionar(width, height)
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            visible = isVisible; enviarEstado()
        }
    }

    // --- «No molestar» ----------------------------------------------------

    private fun ponerNoMolestar() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val actual = nm.currentInterruptionFilter
        if (actual == NotificationManager.INTERRUPTION_FILTER_ALL) {
            noMolestarPrevio = actual
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }

    /** Solo devuelve lo que puso ella, y solo si nadie lo ha cambiado después. */
    private fun quitarNoMolestar() {
        val previo = noMolestarPrevio ?: return
        noMolestarPrevio = null
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            nm.setInterruptionFilter(previo)
        }
    }

    // --- Notificación y final ---------------------------------------------

    private fun notificacion(texto: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Transmisión", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val parar = PendingIntent.getService(this, 1,
            Intent(this, CastService::class.java).setAction(ACCION_DETENER), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("Celiuz Cast")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", parar).build())
            .build()
    }

    private fun actualizarNotificacion() {
        val texto = if (servidor?.conectado == true) "Transmitiendo a la PC" else "Esperando a la PC…"
        getSystemService(NotificationManager::class.java).notify(NOTIFICACION, notificacion(texto))
    }

    private fun detener() {
        if (!activo && proyeccion == null) { stopSelf(); return }
        activo = false
        pcConectada = false
        servidor?.cliente?.cola?.meter(Paquete(Protocolo.FIN, 0, 0, ByteArray(0)))
        Thread.sleep(100) // que el FIN salga
        displayManager?.unregisterDisplayListener(vigiaPantalla)
        audio?.detener(); audio = null
        pantalla?.release(); pantalla = null
        codificador?.detener(); codificador = null
        servidor?.detener(); servidor = null
        proyeccion?.stop(); proyeccion = null
        quitarNoMolestar()
        if (::hilo.isInitialized) hilo.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { if (activo) detener(); super.onDestroy() }

    companion object {
        const val TAG = "CeliuzCast"
        const val CANAL = "transmision"
        const val NOTIFICACION = 1
        const val ACCION_DETENER = "com.celiuz.cast.DETENER"
        const val EXTRA_CODIGO = "codigo"
        const val EXTRA_DATOS = "datos"
        const val EXTRA_CALIDAD = "calidad"
        const val EXTRA_UID = "uid"
        const val EXTRA_NOMBRE_APP = "nombre_app"
        const val EXTRA_MODO = "modo"
        const val EXTRA_NO_MOLESTAR = "no_molestar"

        /** Para que la pantalla de la app sepa si hay transmisión (mismo proceso). */
        @Volatile var activo = false
            private set

        @Volatile var pcConectada = false
            private set
    }
}
