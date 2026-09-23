package com.tony.appbooster.domain.model.performance

/** An explicitly selected launcher app; version identity protects paired measurements. */
data class MeasurementApp(val packageName: String, val label: String, val component: String,
    val versionCode: Long, val lastUpdateTime: Long)

/** Android's activity-launch timings, in milliseconds; never inferred from compiler status. */
data class LaunchTiming(val totalTimeMs: Long, val thisTimeMs: Long?, val waitTimeMs: Long?, val activity: String)

/** Context recorded around every launch; temperature is battery temperature, not CPU temperature. */
data class MeasurementConditions(val batteryTemperatureDeciC: Int?, val batteryPercent: Int?,
    val plugged: Int?, val thermalStatus: Int?, val powerSave: Boolean, val screenInteractive: Boolean)

/** One successful process-cold launch with the exact shell response and surrounding conditions. */
data class LaunchSample(val timing: LaunchTiming, val capturedAtMs: Long,
    val before: MeasurementConditions, val after: MeasurementConditions, val rawOutput: String)

/** A complete five-launch phase, tied to package, firmware and ART identity. */
data class MeasurementPhase(val app: MeasurementApp, val buildFingerprint: String, val artVersion: String,
    val startedAtMs: Long, val finishedAtMs: Long, val elapsedRealtimeMs: Long,
    val samples: List<LaunchSample>, val compilerFilter: String?, val bootCount: Int? = null) {
    /** Summary exists only for a complete capture. */
    val statistics: LaunchStatistics get() = LaunchStatistics.from(samples.map { it.timing.totalTimeMs })
}

/** Verified command evidence, kept distinct from timing evidence. */
data class MeasurementCompile(val startedAtMs: Long, val finishedAtMs: Long, val durationMs: Long,
    val command: String, val exitCode: Int, val outcome: String, val actualFilter: String?,
    val artSizeBeforeBytes: Long?, val artSizeAfterBytes: Long?, val stdout: String, val stderr: String)

/** Durable session; partial samples never count as a completed before/after phase. */
data class PerformanceSession(val id: Long, val app: MeasurementApp, val before: MeasurementPhase? = null,
    val compilation: MeasurementCompile? = null, val after: MeasurementPhase? = null,
    val activeOperation: String? = null, val partialSamples: List<LaunchSample> = emptyList(),
    val message: String? = null, val lastOperationId: String? = null)

/** Median and spread of all five valid samples, including slow samples. */
data class LaunchStatistics(val medianMs: Double, val minMs: Long, val maxMs: Long) {
    /** Positive means the later median was shorter; negative means it was longer. */
    fun improvementPercent(after: LaunchStatistics): Double = (medianMs - after.medianMs) / medianMs * 100.0
    companion object {
        /** Reject incomplete captures and unavailable/zero timings. */
        fun from(samples: List<Long>): LaunchStatistics {
            require(samples.size == 5 && samples.all { it > 0 }) { "Five valid launch samples are required." }
            val sorted = samples.sorted()
            return LaunchStatistics(sorted[2].toDouble(), sorted.first(), sorted.last())
        }
    }
}

/** Returns reasons to withhold a paired performance claim, rather than inventing a score. */
fun comparisonProblems(before: MeasurementPhase, after: MeasurementPhase): List<String> = buildList {
    if (before.app != after.app) add("App version or launcher activity changed. Capture a new baseline.")
    if (listOf(before.artVersion, after.artVersion).any { it.isBlank() || it.equals("unavailable", ignoreCase = true) })
        add("ART version is unavailable; unchanged runtime conditions cannot be verified.")
    if (before.buildFingerprint != after.buildFingerprint || before.artVersion != after.artVersion)
        add("Android or ART changed. Capture a new baseline.")
    if (before.bootCount == null || after.bootCount == null)
        add("Boot identity is unavailable; a restart between phases cannot be ruled out.")
    if ((before.bootCount != null && after.bootCount != null && before.bootCount != after.bootCount) ||
        after.elapsedRealtimeMs < before.elapsedRealtimeMs) add("Device restarted between phases.")
    if (before.samples.size != 5 || after.samples.size != 5) add("A capture is incomplete.")
    if ((before.samples + after.samples).map { it.timing.activity }.distinct().size != 1)
        add("Launches reached different activities.")
    val conditions = (before.samples + after.samples).flatMap { listOf(it.before, it.after) }
    if (conditions.any { !it.screenInteractive }) add("Screen was off during measurement.")
    if (conditions.map { it.powerSave }.distinct().size > 1) add("Power-saving mode changed.")
    if (conditions.any { it.plugged == null }) add("Charging state was unavailable during measurement.")
    if (conditions.map { it.plugged }.distinct().size > 1) add("Charging state changed.")
    if (conditions.any { it.thermalStatus == null }) add("Android thermal status was unavailable during measurement.")
    if (conditions.any { (it.thermalStatus ?: 0) >= 2 }) add("Android reported thermal throttling risk.")
    if (conditions.any { it.batteryTemperatureDeciC == null }) add("Battery temperature was unavailable during measurement.")
    val temps = conditions.mapNotNull { it.batteryTemperatureDeciC }
    if (temps.isNotEmpty() && temps.max() - temps.min() > 20) add("Battery temperature varied by more than 2°C.")
}
