package dev.harness.coughtopause.core

import org.json.JSONObject

/**
 * JSON codecs. Kept out of the Android layer so the exact bytes that survive process death can
 * be round-tripped in a JVM unit test.
 */
object SessionCodec {

    fun encode(session: PersistedSession): String = JSONObject().apply {
        put("v", 1)
        put("scriptId", session.scriptId)
        put("status", session.status.name)
        put("lineIndex", session.lineIndex)
        put("lineId", session.lineId ?: JSONObject.NULL)
        put("reason", session.reason?.name ?: JSONObject.NULL)
        put("pausedAtMs", session.pausedAtMs)
        put("updatedAtEpochMs", session.updatedAtEpochMs)
        put(
            "counters",
            JSONObject().apply {
                put("coughPauses", session.counters.coughPauses)
                put("voicePauses", session.counters.voicePauses)
                put("manualPauses", session.counters.manualPauses)
                put("resumes", session.counters.resumes)
                put("ignoredCoughs", session.counters.ignoredCoughs)
                put("completedLines", session.counters.completedLines)
                put("staleCallbacks", session.counters.staleCallbacks)
            },
        )
    }.toString()

    fun decode(json: String?): PersistedSession? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val status = runCatching { PlaybackStatus.valueOf(o.getString("status")) }
                .getOrDefault(PlaybackStatus.IDLE)
            val reason = o.optString("reason").takeIf { it.isNotEmpty() && it != "null" }
                ?.let { runCatching { PauseReason.valueOf(it) }.getOrNull() }
            val counters = o.optJSONObject("counters")?.let { c ->
                Counters(
                    coughPauses = c.optInt("coughPauses"),
                    voicePauses = c.optInt("voicePauses"),
                    manualPauses = c.optInt("manualPauses"),
                    resumes = c.optInt("resumes"),
                    ignoredCoughs = c.optInt("ignoredCoughs"),
                    completedLines = c.optInt("completedLines"),
                    staleCallbacks = c.optInt("staleCallbacks"),
                )
            } ?: Counters()
            PersistedSession(
                scriptId = o.optString("scriptId", NarrationScript.SCRIPT_ID),
                status = status,
                lineIndex = o.optInt("lineIndex", -1),
                lineId = o.optString("lineId").takeIf { it.isNotEmpty() && it != "null" },
                reason = reason,
                pausedAtMs = o.optLong("pausedAtMs"),
                counters = counters,
                updatedAtEpochMs = o.optLong("updatedAtEpochMs"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

object SettingsCodec {

    fun encode(s: DetectorSettings): String = JSONObject().apply {
        put("v", 1)
        put("backend", s.backend.name)
        put("sensitivity", s.sensitivity)
        put("dspThreshold", s.dspThreshold)
        put("yamnetThreshold", s.yamnetThreshold)
        put("cooldownMs", s.cooldownMs)
        put("windowMs", s.windowMs)
        put("hopMs", s.hopMs)
        put("minPeakDb", s.minPeakDb)
        put("minSnrDb", s.minSnrDb)
        put("confirmationWindows", s.confirmationWindows)
        put("calibrateGain", s.calibrateGain)
    }.toString()

    fun decode(json: String?): DetectorSettings? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            DetectorSettings(
                backend = runCatching { DetectorBackend.valueOf(o.getString("backend")) }
                    .getOrDefault(DetectorBackend.HYBRID),
                sensitivity = o.optDouble("sensitivity", 0.5).toFloat(),
                dspThreshold = o.optDouble("dspThreshold", 0.55).toFloat(),
                yamnetThreshold = o.optDouble("yamnetThreshold", 0.32).toFloat(),
                cooldownMs = o.optInt("cooldownMs", 1200),
                windowMs = o.optInt("windowMs", 500),
                hopMs = o.optInt("hopMs", 250),
                minPeakDb = o.optDouble("minPeakDb", -46.0).toFloat(),
                minSnrDb = o.optDouble("minSnrDb", 9.0).toFloat(),
                confirmationWindows = o.optInt("confirmationWindows", 1),
                calibrateGain = o.optDouble("calibrateGain", 0.0).toFloat(),
            )
        } catch (e: Exception) {
            null
        }
    }
}

object CalibrationCodec {

    fun encode(r: CalibrationResult): String = JSONObject().apply {
        put("threshold", r.threshold)
        put("positives", r.positives)
        put("negatives", r.negatives)
        put("tpr", r.truePositiveRate)
        put("fpr", r.falsePositiveRate)
        put("margin", r.margin)
        put("createdAt", r.createdAtEpochMs)
    }.toString()

    fun decode(json: String?): CalibrationResult? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            CalibrationResult(
                threshold = o.getDouble("threshold").toFloat(),
                positives = o.optInt("positives"),
                negatives = o.optInt("negatives"),
                truePositiveRate = o.optDouble("tpr").toFloat(),
                falsePositiveRate = o.optDouble("fpr").toFloat(),
                margin = o.optDouble("margin").toFloat(),
                createdAtEpochMs = o.optLong("createdAt"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
