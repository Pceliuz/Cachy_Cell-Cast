package com.celiuz.cast

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Process

/** Una app que se puede abrir desde el lanzador. */
class App(val paquete: String, val nombre: String, val uid: Int, val reciente: Boolean) {
    var icono: Drawable? = null
}

/**
 * Las apps del celular, para elegir cuál compartir.
 *
 * Android no deja a una app ver qué otras están abiertas. Lo más cerca que se
 * puede llegar es el «acceso al uso» (lo concede el usuario en Ajustes): con
 * él, las usadas en las últimas horas salen arriba.
 */
object Apps {
    private const val HORAS_RECIENTES = 6

    fun lista(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val recientes = recientes(ctx)
        val lanzables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return lanzables
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != ctx.packageName }
            .distinctBy { it.packageName }
            .map { App(it.packageName, pm.getApplicationLabel(it).toString(), it.uid, it.packageName in recientes) }
            .sortedWith(compareByDescending<App> { it.reciente }.thenBy { it.nombre.lowercase() })
    }

    fun tieneAccesoUso(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
            ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun recientes(ctx: Context): Set<String> {
        if (!tieneAccesoUso(ctx)) return emptySet()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val ahora = System.currentTimeMillis()
        val desde = ahora - HORAS_RECIENTES * 3600_000L
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, desde, ahora)
            .filter { it.lastTimeUsed >= desde && it.totalTimeInForeground > 0 }
            .map { it.packageName }
            .toSet()
    }
}
