package com.celiuz.cast

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * La pantalla de la app: dice qué se va a compartir según el Android del
 * celular (Detector), deja elegir la app, la calidad y «No molestar», y
 * arranca la transmisión.
 */
class MainActivity : Activity() {

    private lateinit var e: Estilo
    private lateinit var prefs: SharedPreferences
    private lateinit var forma: Forma

    private lateinit var botonApp: TextView
    private lateinit var interruptorNoMolestar: Switch
    private lateinit var interruptorSonidoTodas: Switch
    private lateinit var notaNoMolestar: TextView
    private lateinit var botonTransmitir: TextView
    private lateinit var estado: TextView
    private lateinit var pista: TextView

    private val reloj = Handler(Looper.getMainLooper())
    private val hilos = Executors.newFixedThreadPool(2)
    private var apps: List<App> = emptyList()

    private var paqueteApp: String?
        get() = prefs.getString("app", null)
        set(v) = prefs.edit().putString("app", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        e = Estilo(this)
        prefs = getSharedPreferences("ajustes", MODE_PRIVATE)
        forma = Detector.forma(Build.VERSION.SDK_INT, Build.VERSION.RELEASE)
        window.statusBarColor = Paleta.ABISMO
        window.navigationBarColor = Paleta.ABISMO
        setContentView(construir())
        hilos.execute {
            val l = Apps.lista(this)
            runOnUiThread { apps = l; pintarApp() }
        }
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    override fun onPause() {
        super.onPause()
        reloj.removeCallbacksAndMessages(null)
    }

    // --- La pantalla ------------------------------------------------------

    private fun construir(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(e.dp(20), e.dp(28), e.dp(20), e.dp(28))
        }
        fun espacio(dp: Int) = col.addView(View(this), LinearLayout.LayoutParams(1, e.dp(dp)))

        col.addView(e.texto("Celiuz Cast", 26f, Paleta.LUZ, negrita = true))
        col.addView(e.texto("Comparte tu celular con la PC para transmitir", 14f, Paleta.TENUE))
        espacio(20)

        // El detector de versión
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = e.fondo(Paleta.SUPERFICIE, 18, Paleta.APAGADO)
            setPadding(e.dp(18), e.dp(16), e.dp(18), e.dp(16))
            addView(e.texto(forma.titulo, 18f, Paleta.AMATISTA, negrita = true))
            addView(e.texto(if (forma.puedeUnaApp) "Se comparte: una sola app" else "Se comparte: la pantalla entera",
                15f, Paleta.LUZ, negrita = true).apply { setPadding(0, e.dp(6), 0, e.dp(6)) })
            addView(e.texto(forma.detalle, 14f, Paleta.TENUE))
        }
        col.addView(tarjeta)
        espacio(22)

        col.addView(titulo(if (forma.puedeUnaApp) "App a compartir" else "Sonido de"))
        botonApp = e.texto("", 16f).apply {
            background = e.fondo(Paleta.SUPERFICIE, 14, Paleta.APAGADO)
            setPadding(e.dp(16), e.dp(14), e.dp(16), e.dp(14))
            setOnClickListener { elegirApp() }
        }
        col.addView(botonApp)
        pista = e.texto("", 13f, Paleta.TENUE).apply { setPadding(e.dp(4), e.dp(6), e.dp(4), 0) }
        col.addView(pista)
        // Con una app elegida, su sonido y nada más. Esto deja añadir el de
        // las demás (la música de fondo de otra app, p. ej.). La imagen no cambia.
        interruptorSonidoTodas = Switch(this).apply {
            text = "Sonido de todas las apps"
            textSize = 15f
            setTextColor(Paleta.LUZ)
            setPadding(e.dp(4), e.dp(10), 0, 0)
            isChecked = prefs.getBoolean("sonido_todas", false)
            setOnCheckedChangeListener { _, si ->
                prefs.edit().putBoolean("sonido_todas", si).apply()
                pintarApp()
            }
        }
        col.addView(interruptorSonidoTodas)
        espacio(18)

        col.addView(titulo("Calidad"))
        val grupo = RadioGroup(this)
        val elegida = Calidad.de(prefs.getString("calidad", null))
        for (c in Calidad.entries) {
            grupo.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = c.etiqueta
                textSize = 15f
                setTextColor(Paleta.LUZ)
                buttonTintList = android.content.res.ColorStateList.valueOf(Paleta.AMATISTA)
                isChecked = c == elegida
                setOnClickListener { prefs.edit().putString("calidad", c.name).apply() }
            })
        }
        col.addView(grupo)
        col.addView(e.texto("Si la conexión no da para más, baja sola un rato y vuelve a subir.",
            13f, Paleta.TENUE).apply { setPadding(e.dp(4), e.dp(4), e.dp(4), 0) })
        espacio(18)

        interruptorNoMolestar = Switch(this).apply {
            text = "«No molestar» mientras transmites"
            textSize = 15f
            setTextColor(Paleta.LUZ)
            isChecked = prefs.getBoolean("no_molestar", forma.noMolestarRecomendado)
            setOnCheckedChangeListener { _, si ->
                prefs.edit().putBoolean("no_molestar", si).apply()
                if (si && !permisoNoMolestar()) pedirPermisoNoMolestar()
                pintarNoMolestar()
            }
        }
        col.addView(interruptorNoMolestar)
        notaNoMolestar = e.texto("", 13f, Paleta.TENUE).apply {
            setPadding(e.dp(4), e.dp(4), e.dp(4), 0)
            setOnClickListener { if (interruptorNoMolestar.isChecked && !permisoNoMolestar()) pedirPermisoNoMolestar() }
        }
        col.addView(notaNoMolestar)
        espacio(26)

        botonTransmitir = e.boton("Transmitir", principal = true).apply { setOnClickListener { transmitir() } }
        col.addView(botonTransmitir)
        estado = e.texto("", 14f, Paleta.TENUE).apply {
            gravity = Gravity.CENTER
            setPadding(0, e.dp(12), 0, 0)
        }
        col.addView(estado)
        espacio(22)
        col.addView(e.texto("Conecta el celular a la PC por cable (con la depuración USB activada) " +
            "y enciende Celiuz Cast en la PC. En OBS sale como la cámara «Celular» y el micrófono «Celular».",
            13f, Paleta.TENUE))

        return ScrollView(this).apply {
            setBackgroundColor(Paleta.ABISMO)
            isFillViewport = true
            addView(col)
        }
    }

    private fun titulo(t: String) = e.texto(t.uppercase(), 12f, Paleta.TENUE, negrita = true).apply {
        letterSpacing = 0.08f
        setPadding(e.dp(4), 0, 0, e.dp(8))
    }

    private fun appElegida(): App? = apps.firstOrNull { it.paquete == paqueteApp }

    private fun pintarApp() {
        val app = appElegida()
        botonApp.text = when {
            app != null -> app.nombre
            paqueteApp != null && apps.isEmpty() -> "Cargando…"
            else -> if (forma.puedeUnaApp) "Todo el celular" else "Todas las apps"
        }
        interruptorSonidoTodas.visibility = if (app != null) View.VISIBLE else View.GONE
        val todas = app != null && interruptorSonidoTodas.isChecked
        val sonido = if (todas) "Se oirán todas las apps (música incluida), no solo ${app!!.nombre}."
                     else "Se oirá solo ${app?.nombre}."
        pista.text = when {
            app != null && forma.puedeUnaApp ->
                "Al transmitir, Android te preguntará qué compartir: elige «Una sola app» y luego ${app.nombre}. $sonido"
            app != null -> "$sonido La imagen es la pantalla entera."
            forma.puedeUnaApp -> "Se compartirá la pantalla entera con el sonido de todas las apps."
            else -> "Se oirá todo lo que suene en el celular (menos avisos y llamadas)."
        }
    }

    private fun pintarNoMolestar() {
        notaNoMolestar.text = when {
            !interruptorNoMolestar.isChecked && forma.noMolestarRecomendado ->
                "Apagado: si te llega un mensaje, se verá en el stream."
            interruptorNoMolestar.isChecked && !permisoNoMolestar() ->
                "Falta darle permiso: toca aquí para ir a Ajustes."
            interruptorNoMolestar.isChecked -> "Al parar la transmisión vuelve como estaba."
            else -> ""
        }
    }

    private fun refrescar() {
        val activo = CastService.activo
        botonTransmitir.text = if (activo) "Detener" else "Transmitir"
        botonTransmitir.background = e.fondo(if (activo) Paleta.APAGADO else Paleta.VIOLETA, 14,
            if (activo) Paleta.ALERTA else null)
        estado.text = when {
            !activo -> ""
            CastService.pcConectada -> "● Transmitiendo a la PC"
            else -> "Esperando a la PC… (¿está el cable puesto y Celiuz Cast encendido en la PC?)"
        }
        estado.setTextColor(if (CastService.pcConectada) Paleta.EXITO else Paleta.ATENCION)
        pintarNoMolestar()
        reloj.postDelayed(::refrescar, 700)
    }

    // --- Elegir app -------------------------------------------------------

    private fun elegirApp() {
        val todas = listOf<App?>(null) + apps
        var visibles = todas
        val lista = ListView(this).apply { divider = null; setBackgroundColor(Paleta.SUPERFICIE) }
        val adaptador = object : BaseAdapter() {
            override fun getCount() = visibles.size
            override fun getItem(i: Int) = visibles[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, reusar: View?, padre: ViewGroup?): View {
                val fila = (reusar as? LinearLayout) ?: filaApp()
                val app = visibles[i]
                val icono = fila.getChildAt(0) as ImageView
                val nombre = fila.getChildAt(1) as TextView
                nombre.text = app?.nombre ?: if (forma.puedeUnaApp) "Todo el celular" else "Todas las apps"
                if (app?.reciente == true) nombre.append("  · abierta hace poco")
                icono.tag = app?.paquete
                icono.setImageDrawable(app?.icono)
                if (app != null && app.icono == null) hilos.execute {
                    val d = try { packageManager.getApplicationIcon(app.paquete) } catch (_: PackageManager.NameNotFoundException) { null }
                    app.icono = d
                    runOnUiThread { if (icono.tag == app.paquete) icono.setImageDrawable(d) }
                }
                return fila
            }
        }
        lista.adapter = adaptador
        val buscar = EditText(this).apply {
            hint = "Buscar"
            setTextColor(Paleta.LUZ)
            setHintTextColor(Paleta.TENUE)
            background = e.fondo(Paleta.APAGADO, 12)
            setPadding(e.dp(14), e.dp(10), e.dp(14), e.dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase()
                    visibles = if (q.isEmpty()) todas else todas.filter { it != null && q in it.nombre.lowercase() }
                    adaptador.notifyDataSetChanged()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(e.dp(16), e.dp(16), e.dp(16), 0)
            setBackgroundColor(Paleta.SUPERFICIE)
            addView(buscar)
            if (!Apps.tieneAccesoUso(this@MainActivity)) addView(e.texto(
                "Para ver arriba las apps que tienes abiertas, toca aquí y dale «acceso al uso».",
                13f, Paleta.AMATISTA).apply {
                    setPadding(e.dp(4), e.dp(10), e.dp(4), e.dp(4))
                    setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                })
            addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, e.dp(420)))
        }
        val dialogo = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setView(caja).create()
        lista.setOnItemClickListener { _, _, i, _ ->
            paqueteApp = visibles[i]?.paquete
            pintarApp()
            dialogo.dismiss()
        }
        dialogo.show()
    }

    private fun filaApp() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(e.dp(6), e.dp(10), e.dp(6), e.dp(10))
        addView(ImageView(context), LinearLayout.LayoutParams(e.dp(36), e.dp(36)))
        addView(e.texto("", 15f).apply { setPadding(e.dp(14), 0, 0, 0) })
    }

    // --- Transmitir -------------------------------------------------------

    private fun transmitir() {
        if (CastService.activo) {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACCION_DETENER))
            return
        }
        val faltan = buildList {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(android.Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (faltan.isNotEmpty() && !prefs.getBoolean("permisos_pedidos", false)) {
            prefs.edit().putBoolean("permisos_pedidos", true).apply()
            requestPermissions(faltan.toTypedArray(), PEDIR_PERMISOS)
            return
        }
        pedirProyeccion()
    }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) {
        if (codigo == PEDIR_PERMISOS) pedirProyeccion()
    }

    private fun pedirProyeccion() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Con una app elegida, Android deja escoger «una sola app»; sin ella,
            // directamente la pantalla entera.
            mpm.createScreenCaptureIntent(if (appElegida() != null)
                MediaProjectionConfig.createConfigForUserChoice()
            else MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PEDIR_PROYECCION)
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(codigo, resultado, datos)
        if (codigo != PEDIR_PROYECCION || resultado != RESULT_OK || datos == null) return
        val app = appElegida()
        val modo = if (forma.puedeUnaApp && app != null) "app" else "pantalla"
        startForegroundService(Intent(this, CastService::class.java)
            .putExtra(CastService.EXTRA_CODIGO, resultado)
            .putExtra(CastService.EXTRA_DATOS, datos)
            .putExtra(CastService.EXTRA_CALIDAD, Calidad.de(prefs.getString("calidad", null)).name)
            .putExtra(CastService.EXTRA_UID, if (interruptorSonidoTodas.isChecked) -1 else app?.uid ?: -1)
            .putExtra(CastService.EXTRA_NOMBRE_APP, app?.nombre)
            .putExtra(CastService.EXTRA_MODO, modo)
            .putExtra(CastService.EXTRA_NO_MOLESTAR, interruptorNoMolestar.isChecked))
        // Y se abre la app elegida, para que el usuario no tenga que buscarla.
        app?.let { a ->
            packageManager.getLaunchIntentForPackage(a.paquete)?.let { abrir ->
                reloj.postDelayed({ startActivity(abrir) }, 600)
            }
        }
    }

    // --- «No molestar» ----------------------------------------------------

    private fun permisoNoMolestar() =
        getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun pedirPermisoNoMolestar() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Permiso para «No molestar»")
            .setMessage("Para encenderlo y apagarlo solo, Celiuz Cast necesita acceso a «No molestar». " +
                "En la lista que se abre, busca Celiuz Cast y actívalo.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    override fun onDestroy() {
        hilos.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val PEDIR_PERMISOS = 1
        const val PEDIR_PROYECCION = 2
    }
}
