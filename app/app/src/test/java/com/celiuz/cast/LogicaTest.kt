package com.celiuz.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FormaTest {
    @Test fun android14ComparteUnaApp() {
        val f = Detector.forma(34, "14")
        assertEquals("app", f.modo)
        assertTrue(f.puedeUnaApp)
        assertEquals("Tienes Android 14", f.titulo)
    }

    @Test fun android13ComparteLaPantallaYRecomiendaNoMolestar() {
        val f = Detector.forma(33, "13")
        assertEquals("pantalla", f.modo)
        assertFalse(f.puedeUnaApp)
        assertTrue(f.noMolestarRecomendado)
        assertTrue(f.detalle.contains("pantalla entera"))
    }

    @Test fun losMasNuevosTambienUnaApp() {
        assertTrue(Detector.forma(36, "16").puedeUnaApp)
    }
}

class TamanoTest {
    @Test fun elHonorX7bCabeEn1920() {
        // 1080x2412 lo rechaza su codificador; a 1920 de lado mayor funciona.
        assertEquals(848 to 1920, Tamano.para(1080, 2412, 1920))
    }

    @Test fun girado() { assertEquals(1920 to 848, Tamano.para(2412, 1080, 1920)) }

    @Test fun nuncaAgranda() { assertEquals(720 to 1280, Tamano.para(720, 1280, 1920)) }

    @Test fun multiplosDe16() {
        val (w, h) = Tamano.para(1080, 2400, 1280)
        assertEquals(0, w % 16); assertEquals(0, h % 16)
    }

    @Test fun escalonesDeMayorAMenor() {
        assertEquals(listOf(1920, 1600, 1280, 1088, 1024, 800), Tamano.escalones(1920))
        assertEquals(listOf(1280, 1088, 1024, 800), Tamano.escalones(1280))
    }
}

class ColaTest {
    private fun img(pts: Long, clave: Boolean = false) =
        Paquete(Protocolo.VIDEO, if (clave) Protocolo.CLAVE else 0, pts, ByteArray(10))
    private fun audio(pts: Long) = Paquete(Protocolo.AUDIO, 0, pts, ByteArray(4))

    @Test fun sinClaveNoEntraNingunaImagen() {
        val c = Cola()
        c.meterVideo(img(0)); c.meterVideo(img(16_000))
        assertEquals(0, c.tamano)
        c.meterVideo(img(32_000, clave = true)); c.meterVideo(img(48_000))
        assertEquals(2, c.tamano)
    }

    @Test fun laConfiguracionPasaSiempre() {
        val c = Cola()
        c.meterVideo(Paquete(Protocolo.VIDEO, Protocolo.CONFIG, 0, ByteArray(5)))
        assertEquals(1, c.tamano)
    }

    @Test fun unAtascoTiraLasImagenesYEsperaClave() {
        val c = Cola(limiteVideoUs = 250_000)
        c.meterVideo(img(0, clave = true))
        for (i in 1..10) assertFalse(c.meterVideo(img(i * 16_000L)))
        c.meter(audio(1))
        assertTrue("300 ms en cola es un atasco", c.meterVideo(img(300_000)))
        assertEquals("solo queda el sonido", 1, c.tamano)
        assertTrue(c.esperandoClave)
        assertFalse(c.meterVideo(img(316_000)))
        assertEquals(1, c.tamano)
    }

    @Test fun siLaQueAtascaEsClaveSeQueda() {
        val c = Cola()
        c.meterVideo(img(0, clave = true))
        assertTrue(c.meterVideo(img(400_000, clave = true)))
        assertEquals(1, c.tamano)
        assertFalse(c.esperandoClave)
    }

    @Test fun elSonidoAcumuladoTiraElMasViejo() {
        val c = Cola(limiteAudio = 3)
        for (i in 1..5L) c.meterAudio(audio(i))
        assertEquals(3, c.tamano)
        assertEquals(3L, c.sacar(0)!!.pts)
    }

    @Test fun cerradaDevuelveNull() {
        val c = Cola(); c.cerrar()
        assertNull(c.sacar(10))
    }
}

class ProtocoloTest {
    @Test fun cabeceraIdaYVuelta() {
        val datos = byteArrayOf(1, 2, 3)
        val bytes = Protocolo.cabecera(Protocolo.VIDEO, Protocolo.CLAVE, 123456789L, 3) + datos
        val m = Protocolo.leer(ByteArrayInputStream(bytes))
        assertEquals(Protocolo.VIDEO, m.tipo)
        assertEquals(Protocolo.CLAVE, m.banderas)
        assertEquals(123456789L, m.pts)
        assertEquals(datos.toList(), m.datos.toList())
    }

    @Test fun json() {
        assertEquals("""{"a":1,"b":"x\"y","c":null,"d":true}""",
            String(Protocolo.json("a" to 1, "b" to "x\"y", "c" to null, "d" to true)))
    }
}
