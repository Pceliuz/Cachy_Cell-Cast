package com.celiuz.cast

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/** La paleta del escritorio Celiuz (hypr/conf/colores.conf de los dotfiles). */
object Paleta {
    val ABISMO = Color.parseColor("#0d0418")
    val SUPERFICIE = Color.parseColor("#1a0830")
    val APAGADO = Color.parseColor("#2d1b4e")
    val VIOLETA = Color.parseColor("#6a00f4")
    val AMATISTA = Color.parseColor("#b16cff")
    val LUZ = Color.parseColor("#e4c7ff")
    val TENUE = Color.parseColor("#8a7aa8")
    val ALERTA = Color.parseColor("#eb6f92")
    val ATENCION = Color.parseColor("#f6c177")
    val EXITO = Color.parseColor("#7dffb0")
}

class Estilo(private val ctx: Context) {
    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
        ctx.resources.displayMetrics).toInt()

    fun fondo(color: Int, radio: Int = 16, borde: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radio).toFloat()
        if (borde != null) setStroke(dp(1), borde)
    }

    fun texto(t: CharSequence, tam: Float = 15f, color: Int = Paleta.LUZ, negrita: Boolean = false) =
        TextView(ctx).apply {
            text = t
            textSize = tam
            setTextColor(color)
            if (negrita) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    fun boton(t: String, principal: Boolean = false) = Button(ctx).apply {
        text = t
        isAllCaps = false
        textSize = if (principal) 18f else 15f
        setTextColor(if (principal) Color.WHITE else Paleta.LUZ)
        background = fondo(if (principal) Paleta.VIOLETA else Paleta.APAGADO, 14)
        gravity = Gravity.CENTER
        stateListAnimator = null
        setPadding(dp(16), dp(if (principal) 18 else 12), dp(16), dp(if (principal) 18 else 12))
    }
}
