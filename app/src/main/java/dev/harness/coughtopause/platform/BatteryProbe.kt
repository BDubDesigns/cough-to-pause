package dev.harness.coughtopause.platform

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Battery observation for the harness report.
 *
 * Uses the charge counter (µAh) for a real %/hour figure when the device exposes it, and falls
 * back to the instantaneous current for a rough drain estimate. Nothing here is inferred from
 * "feels warm"; every number is read from BatteryManager.
 */
class BatteryProbe(context: Context) {

    private val manager = context.getSystemService(BatteryManager::class.java)
    private var firstChargeUah: Long? = null
    private var firstAtMs: Long = 0
    private var lastChargeUah: Long? = null
    private var lastAtMs: Long = 0
    private var currentSum = 0.0
    private var currentSamples = 0

    fun sample(): BatteryView {
        val bm = manager ?: return BatteryView(note = "no BatteryManager")
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val currentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val chargeUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
        val now = SystemClock.elapsedRealtime()

        if (currentNow != Int.MIN_VALUE && currentNow != 0) {
            currentSum += currentNow
            currentSamples++
        }
        if (firstChargeUah == null && chargeUah > 0) {
            firstChargeUah = chargeUah
            firstAtMs = now
        }
        if (chargeUah > 0) {
            lastChargeUah = chargeUah
            lastAtMs = now
        }

        val pctPerHour = run {
            val first = firstChargeUah ?: return@run Float.NaN
            val last = lastChargeUah ?: return@run Float.NaN
            val hours = (lastAtMs - firstAtMs) / 3_600_000f
            if (hours < 0.02f) return@run Float.NaN
            // Phone batteries here are ~4500-5000 mAh; use the counter ratio, not a guessed capacity.
            val dropPct = ((first - last) / first.toFloat()) * 100f
            dropPct / hours
        }

        return BatteryView(
            levelPercent = level,
            charging = charging,
            currentNowUa = currentNow,
            temperatureC = Float.NaN,
            drainMilliAmpAvg = if (currentSamples > 0) (currentSum / currentSamples / 1000.0).toFloat() else Float.NaN,
            batteryPctPerHour = pctPerHour,
            note = when {
                charging -> "charging — drain measurement paused"
                pctPerHour.isNaN() -> "collecting (${(lastAtMs - firstAtMs).coerceAtLeast(0) / 1000}s)"
                else -> "over ${((lastAtMs - firstAtMs) / 60000f).coerceAtLeast(0f).toInt()} min"
            },
        )
    }
}
