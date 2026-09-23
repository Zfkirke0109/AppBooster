package com.tony.appbooster.data.performance

import com.tony.appbooster.domain.model.performance.*
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, lossless measurement export; also used for atomic private session persistence. */
internal object PerformanceSessionJson {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
    private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
    private fun app(a: MeasurementApp) = obj("packageName" to a.packageName, "label" to a.label, "component" to a.component,
        "versionCode" to a.versionCode, "lastUpdateTime" to a.lastUpdateTime)
    private fun readApp(a: JSONObject) = MeasurementApp(a.getString("packageName"), a.getString("label"),
        a.getString("component"), a.getLong("versionCode"), a.getLong("lastUpdateTime"))
    private fun conditions(c: MeasurementConditions) = obj("batteryTemperatureDeciC" to c.batteryTemperatureDeciC,
        "batteryPercent" to c.batteryPercent, "plugged" to c.plugged, "thermalStatus" to c.thermalStatus,
        "powerSave" to c.powerSave, "screenInteractive" to c.screenInteractive)
    private fun readConditions(c: JSONObject) = MeasurementConditions(c.longOrNull("batteryTemperatureDeciC")?.toInt(),
        c.longOrNull("batteryPercent")?.toInt(), c.longOrNull("plugged")?.toInt(), c.longOrNull("thermalStatus")?.toInt(),
        c.getBoolean("powerSave"), c.getBoolean("screenInteractive"))
    private fun sample(s: LaunchSample) = obj("totalTimeMs" to s.timing.totalTimeMs, "thisTimeMs" to s.timing.thisTimeMs,
        "waitTimeMs" to s.timing.waitTimeMs, "activity" to s.timing.activity, "capturedAtMs" to s.capturedAtMs,
        "conditionsBefore" to conditions(s.before), "conditionsAfter" to conditions(s.after), "rawOutput" to s.rawOutput)
    private fun samples(s: List<LaunchSample>) = JSONArray().apply { s.forEach { put(sample(it)) } }
    private fun readSamples(a: JSONArray) = (0 until a.length()).map { i -> a.getJSONObject(i).let { s ->
        LaunchSample(LaunchTiming(s.getLong("totalTimeMs"), s.longOrNull("thisTimeMs"), s.longOrNull("waitTimeMs"), s.getString("activity")),
            s.getLong("capturedAtMs"), readConditions(s.getJSONObject("conditionsBefore")), readConditions(s.getJSONObject("conditionsAfter")), s.getString("rawOutput")) } }
    private fun phase(p: MeasurementPhase?) = p?.let { obj("app" to app(p.app), "buildFingerprint" to p.buildFingerprint,
        "artVersion" to p.artVersion, "startedAtMs" to p.startedAtMs, "finishedAtMs" to p.finishedAtMs,
        "elapsedRealtimeMs" to p.elapsedRealtimeMs, "samples" to samples(p.samples), "compilerFilter" to p.compilerFilter) }
    private fun readPhase(p: JSONObject?) = p?.let { MeasurementPhase(readApp(p.getJSONObject("app")), p.getString("buildFingerprint"),
        p.getString("artVersion"), p.getLong("startedAtMs"), p.getLong("finishedAtMs"), p.getLong("elapsedRealtimeMs"),
        readSamples(p.getJSONArray("samples")), p.stringOrNull("compilerFilter")) }
    private fun compilation(c: MeasurementCompile?) = c?.let { obj("startedAtMs" to c.startedAtMs, "finishedAtMs" to c.finishedAtMs,
        "durationMs" to c.durationMs, "command" to c.command, "exitCode" to c.exitCode, "outcome" to c.outcome,
        "actualFilter" to c.actualFilter, "artSizeBeforeBytes" to c.artSizeBeforeBytes, "artSizeAfterBytes" to c.artSizeAfterBytes,
        "stdout" to c.stdout, "stderr" to c.stderr) }
    private fun readCompile(c: JSONObject?) = c?.let { MeasurementCompile(c.getLong("startedAtMs"), c.getLong("finishedAtMs"), c.getLong("durationMs"),
        c.getString("command"), c.getInt("exitCode"), c.getString("outcome"), c.stringOrNull("actualFilter"),
        c.longOrNull("artSizeBeforeBytes"), c.longOrNull("artSizeAfterBytes"), c.getString("stdout"), c.getString("stderr")) }
    fun encode(s: PerformanceSession): String = obj("schemaVersion" to 1, "sessionId" to s.id,
        "protocol" to "Five am start -S -W process-cold launches per phase; 3 second settling interval; no cache/profile reset",
        "interpretation" to "Observed startup change, not proof of dex2oat-only causation. First-frame launch time is not full usability, FPS or battery savings.",
        "app" to app(s.app), "before" to phase(s.before), "compilation" to compilation(s.compilation), "after" to phase(s.after),
        "activeOperation" to s.activeOperation, "partialSamples" to samples(s.partialSamples), "message" to s.message).toString(2)
    fun decode(raw: String): PerformanceSession = JSONObject(raw).let { s ->
        require(s.getInt("schemaVersion") == 1)
        PerformanceSession(s.getLong("sessionId"), readApp(s.getJSONObject("app")), readPhase(s.optJSONObject("before")),
            readCompile(s.optJSONObject("compilation")), readPhase(s.optJSONObject("after")), s.stringOrNull("activeOperation"),
            readSamples(s.getJSONArray("partialSamples")), s.stringOrNull("message"))
    }
}
