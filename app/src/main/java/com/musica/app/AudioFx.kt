package com.musica.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/** Estado de audio compartido entre el servicio y la pantalla de ajustes (mismo proceso). */
object AudioFx {
    @Volatile var eq: Equalizer? = null

    fun prefs(ctx: Context) = ctx.getSharedPreferences("musica_prefs", Context.MODE_PRIVATE)

    fun attach(ctx: Context, sessionId: Int) {
        try { eq?.release() } catch (e: Exception) { }
        eq = try { Equalizer(0, sessionId) } catch (e: Exception) { null }
        applyEq(ctx)
        applyMode(ctx)
    }

    @Volatile var player: Player? = null
    @Volatile var sleepEnd = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val sleepTask = Runnable { player?.pause(); sleepEnd = 0L }

    fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepTask)
        if (minutes > 0) {
            sleepEnd = System.currentTimeMillis() + minutes * 60000L
            handler.postDelayed(sleepTask, minutes * 60000L)
        } else sleepEnd = 0L
    }

    fun release() { try { eq?.release() } catch (e: Exception) { }; eq = null }

    /** El ecualizador solo actúa en modo envolvente; en Hi-Fi puro queda desactivado. */
    fun applyEq(ctx: Context) {
        val e = eq ?: return
        val p = prefs(ctx)
        try {
            val min = e.bandLevelRange[0].toInt(); val max = e.bandLevelRange[1].toInt()
            for (i in 0 until e.numberOfBands.toInt())
                e.setBandLevel(i.toShort(), p.getInt("band$i", 0).coerceIn(min, max).toShort())
            e.enabled = p.getString("mode", "hifi") == "surround" && p.getBoolean("eq_on", false)
        } catch (ex: Exception) { }
    }

    /** Hi-Fi: intenta salida bit-perfect (Android 14+, DAC USB). Envolvente: la desactiva. */
    fun applyMode(ctx: Context): String {
        val hifi = prefs(ctx).getString("mode", "hifi") == "hifi"
        if (Build.VERSION.SDK_INT < 34)
            return if (hifi) "Salida Hi-Fi sin efectos (bit-perfect requiere Android 14 o superior)"
                   else "Modo envolvente: el sistema puede aplicar Dolby Atmos"
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            if (dev == null) {
                if (hifi) "Hi-Fi sin efectos. Conecta un DAC USB para salida bit-perfect"
                else "Modo envolvente: el sistema puede aplicar Dolby Atmos"
            } else if (!hifi) {
                am.clearPreferredMixerAttributes(attrs, dev)
                "Modo envolvente: el sistema puede aplicar Dolby Atmos"
            } else {
                val mix = am.getSupportedMixerAttributes(dev).firstOrNull {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
                if (mix != null && am.setPreferredMixerAttributes(attrs, dev, mix))
                    "Bit-perfect activo en ${dev.productName}"
                else "Tu DAC no ofrece bit-perfect; se usa salida Hi-Fi estándar"
            }
        } catch (e: Exception) { "No se pudo configurar bit-perfect en este equipo" }
    }

    fun spatialStatus(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 32) return "Audio espacial de Android: requiere Android 12L o superior"
        val sp = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).spatializer
        return when {
            sp.isAvailable && sp.isEnabled -> "Audio espacial del sistema: activo"
            sp.isAvailable -> "Audio espacial del sistema: disponible, desactivado"
            else -> "Audio espacial de Android: no disponible (Dolby Atmos se activa en los ajustes del sistema)"
        }
    }
}
