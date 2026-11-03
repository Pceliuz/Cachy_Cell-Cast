package com.celiuz.cast

/**
 * El detector de versión: qué se puede compartir según el Android del celular.
 *
 * - Android 14 (API 34) en adelante deja compartir UNA sola app: Android
 *   pregunta cuál, y lo demás del celular (notificaciones, otras apps) no sale.
 * - Android 10 a 13 solo comparte la pantalla entera. Por eso se ofrece
 *   «No molestar»: si no, una notificación con tus mensajes sale en el stream.
 * - El sonido por app funciona desde Android 10 (API 29), que es el mínimo de
 *   la app.
 *
 * Sin nada de Android dentro: se prueba en la PC (FormaTest).
 */
data class Forma(
    val modo: String,               // "app" o "pantalla"
    val titulo: String,
    val detalle: String,
    val puedeUnaApp: Boolean,
    val noMolestarRecomendado: Boolean,
)

object Detector {
    const val SDK_UNA_APP = 34

    fun forma(sdk: Int, version: String): Forma =
        if (sdk >= SDK_UNA_APP) Forma(
            modo = "app",
            titulo = "Tienes Android $version",
            detalle = "Se compartirá solo la app que elijas, con su sonido. " +
                "Tus notificaciones y el resto del celular no salen en el stream.",
            puedeUnaApp = true,
            noMolestarRecomendado = false,
        ) else Forma(
            modo = "pantalla",
            titulo = "Tienes Android $version",
            detalle = "Tu Android no deja compartir una sola app, así que se compartirá " +
                "la pantalla entera, con el sonido de la app que elijas. " +
                "Deja «No molestar» encendido para que tus notificaciones no salgan en el stream.",
            puedeUnaApp = false,
            noMolestarRecomendado = true,
        )
}
