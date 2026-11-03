package com.celiuz.cast

/**
 * Las calidades que se ofrecen y el tamaño del vídeo que sale de cada una.
 * Sin nada de Android dentro: se prueba en la PC (CalidadTest).
 */
enum class Calidad(val etiqueta: String, val ladoMax: Int, val bitrate: Int, val fps: Int) {
    ALTA("Alta · 1080p 60", 1920, 12_000_000, 60),
    EQUILIBRADA("Equilibrada · 720p 60", 1280, 6_000_000, 60),
    AHORRO("Ahorro · 720p 30", 1280, 3_000_000, 30);

    companion object {
        fun de(nombre: String?): Calidad = entries.firstOrNull { it.name == nombre } ?: ALTA
    }
}

object Tamano {
    /**
     * Lados que se prueban, de mayor a menor, cuando el codificador del celular
     * no acepta un tamaño. Pasó con el HONOR X7b: a 1080x2412 el codificador
     * falla al configurarse y a 1920 de lado mayor funciona.
     *
     * El 1088 es para el celular EN VERTICAL: ese mismo codificador no pasa de
     * 1088 de alto, y sin ese escalón saltaba de 1280 a 1024 (448x1024). La
     * cámara de la PC mide 1080 de alto, así que más no se vería.
     */
    fun escalones(ladoMax: Int): List<Int> =
        (listOf(ladoMax) + listOf(1600, 1280, 1088, 1024, 800).filter { it < ladoMax }).distinct()

    /**
     * El tamaño del vídeo para un contenido de [ancho]x[alto] sin pasar de
     * [ladoMax] en el lado mayor, con la misma forma y en múltiplos de 16
     * (lo que aceptan todos los codificadores). Nunca agranda.
     */
    fun para(ancho: Int, alto: Int, ladoMax: Int): Pair<Int, Int> {
        require(ancho > 0 && alto > 0) { "tamaño imposible: ${ancho}x$alto" }
        val escala = minOf(1.0, ladoMax.toDouble() / maxOf(ancho, alto))
        fun redondear(v: Double) = maxOf(16, (v / 16).toInt() * 16)
        return redondear(ancho * escala) to redondear(alto * escala)
    }
}
