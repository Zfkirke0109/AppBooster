package com.tony.appbooster.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tony.appbooster.data.performance.PerformanceSessionJson
import com.tony.appbooster.data.performance.StartupOutputParser
import com.tony.appbooster.domain.model.performance.LaunchSample
import com.tony.appbooster.domain.model.performance.LaunchTiming
import com.tony.appbooster.domain.model.performance.MeasurementApp
import com.tony.appbooster.domain.model.performance.MeasurementCompile
import com.tony.appbooster.domain.model.performance.MeasurementConditions
import com.tony.appbooster.domain.model.performance.MeasurementPhase
import com.tony.appbooster.domain.model.performance.PerformanceSession
import com.tony.appbooster.presentation.screen.performance.PerformanceContent
import com.tony.appbooster.presentation.viewmodel.performance.PerformanceUiModel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Android-runtime coverage using recorded-shaped samples; these tests never launch a measured app. */
@RunWith(AndroidJUnit4::class)
class PerformanceMeasurementRuntimeTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var originalLocale: Locale

    @Before
    fun useStableNumberFormatting() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreNumberFormatting() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun coldLaunchOutputParsesOnAndroidWithCrLfAndOptionalTimings() {
        val output = coldOutput(437).replace("\n", "\r\n")
        assertEquals(
            LaunchTiming(437L, 420L, 451L, APP.component),
            StartupOutputParser.parse(output, APP.packageName)
        )
        val missingOptionalTimings = coldOutput(437)
            .lineSequence()
            .filterNot { it.startsWith("ThisTime:") || it.startsWith("WaitTime:") }
            .joinToString("\n")
        val parsed = StartupOutputParser.parse(missingOptionalTimings, APP.packageName)
        assertEquals(437L, parsed.totalTimeMs)
        assertNull(parsed.thisTimeMs)
        assertNull(parsed.waitTimeMs)
    }

    @Test
    fun warmIncompleteAmbiguousOrWrongAppOutputCannotBecomeAColdSample() {
        val complete = coldOutput(437)
        val invalidOutputs = listOf(
            complete.replace("LaunchState: COLD", "LaunchState: WARM"),
            complete.replace("Status: ok", "Status: timeout"),
            complete.replace("Complete", ""),
            complete.replace("TotalTime: 437", "TotalTime: 0"),
            complete.replace("TotalTime: 437", "TotalTime: unavailable"),
            complete.replace(APP.component, "com.example.measurement.other/.MainActivity"),
            "$complete\nStatus: ok",
            "$complete\nTotalTime: 100",
            "$complete\nError: launch failed",
            "$complete\n[output truncated]"
        )
        invalidOutputs.forEachIndexed { index, output ->
            assertThrows("Invalid sample $index must be excluded", IllegalArgumentException::class.java) {
                StartupOutputParser.parse(output, APP.packageName)
            }
        }
    }

    @Test
    fun jsonRoundTripKeepsRawSamplesConditionsNullsAndCompileEvidence() {
        val raw = "Status: ok\r\nActivity: ${APP.component}\r\nquoted=\"ART\" · Ω\nComplete"
        val first = sample(101L, 1_701_000_000_001L).copy(
            timing = LaunchTiming(101L, null, 0L, APP.component),
            before = CONDITIONS.copy(batteryTemperatureDeciC = null, thermalStatus = null),
            after = CONDITIONS.copy(batteryPercent = null, plugged = null),
            rawOutput = raw
        )
        val before = phase(listOf(101, 800, 200, 300, 400), 10_000L).let {
            it.copy(samples = listOf(first) + it.samples.drop(1), compilerFilter = null)
        }
        val compile = MeasurementCompile(
            startedAtMs = 1_701_000_000_050L,
            finishedAtMs = 1_701_000_002_050L,
            durationMs = 2_000L,
            command = "cmd package compile -m speed -f --full ${APP.packageName}",
            exitCode = 0,
            outcome = "OS_ADJUSTED_FILTER",
            actualFilter = "speed-profile",
            artSizeBeforeBytes = null,
            artSizeAfterBytes = 9_000_000_000L,
            stdout = "DexContainerFileDexoptResult{compilerFilter=speed-profile}\nquoted=\"done\"",
            stderr = "ART diagnostic\nsecond line"
        )
        val session = PerformanceSession(
            id = 1_701_000_000_000L,
            app = APP,
            before = before,
            compilation = compile,
            after = phase(listOf(110, 220, 330, 440, 990), 30_000L),
            activeOperation = "AFTER",
            partialSamples = listOf(first),
            message = "Interrupted after one sample\nResults remain partial."
        )

        val encoded = PerformanceSessionJson.encode(session)
        val decoded = PerformanceSessionJson.decode(encoded)
        assertEquals(session, decoded)
        // Inspect exported fields independently, so paired encoder/decoder renaming cannot hide data loss.
        val json = JSONObject(encoded)
        val exportedFirst = json.getJSONObject("before").getJSONArray("samples").getJSONObject(0)
        assertEquals(5, json.getJSONObject("before").getJSONArray("samples").length())
        assertEquals(raw, exportedFirst.getString("rawOutput"))
        assertTrue(exportedFirst.isNull("thisTimeMs"))
        assertEquals(0L, exportedFirst.getLong("waitTimeMs"))
        assertTrue(exportedFirst.getJSONObject("conditionsBefore").isNull("thermalStatus"))
        assertEquals(9_000_000_000L, json.getJSONObject("compilation").getLong("artSizeAfterBytes"))
        assertEquals(compile.stdout, json.getJSONObject("compilation").getString("stdout"))
        assertEquals(compile.stderr, json.getJSONObject("compilation").getString("stderr"))
    }

    @Test
    fun jsonDoesNotInventMissingPhasesOrUnavailableCompileEvidence() {
        val empty = PerformanceSession(id = 42L, app = APP)
        val encoded = PerformanceSessionJson.encode(empty)
        assertEquals(empty, PerformanceSessionJson.decode(encoded))
        val json = JSONObject(encoded)
        listOf("before", "after", "compilation", "activeOperation", "message").forEach {
            assertTrue("$it must remain JSON null", json.isNull(it))
        }
        assertEquals(0, json.getJSONArray("partialSamples").length())

        val unavailable = MeasurementCompile(10L, 20L, 10L, "recorded command", 1,
            "FAILED_OR_REFUSED", null, null, null, "", "refused")
        val failed = empty.copy(compilation = unavailable)
        assertEquals(failed, PerformanceSessionJson.decode(PerformanceSessionJson.encode(failed)))
    }

    @Test
    fun screenDisplaysActualMediansAndLongerAfterWithoutDroppingSlowSamples() {
        showSession(comparisonSession())
        // Hand-derived medians: sorted Before [100, 200, 300, 500, 9000],
        // After [150, 350, 400, 450, 900]. The later median is 33.3% longer.
        compose.onNodeWithText("300.0 ms median").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Samples: 500, 100, 300, 9000, 200 ms").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Range 100–9000 ms", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("400.0 ms median").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("33.3% longer median launch time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("33.3% shorter", substring = true).assertDoesNotExist()
    }

    @Test
    fun updatedAppIdentityKeepsRawMeasurementsButSuppressesPairedPercentage() {
        val session = comparisonSession()
        val after = requireNotNull(session.after).copy(app = APP.copy(versionCode = 8L))
        showSession(session.copy(after = after))
        compose.onNodeWithText("300.0 ms median").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("400.0 ms median").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Comparison inconclusive").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("App version or launcher activity changed", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("median launch time", substring = true).assertDoesNotExist()
    }

    @Test
    fun startupResultsExplicitlyExcludeBatterySavingsAndFpsMeasurements() {
        showSession(comparisonSession())
        val scopeDisclosure = hasText("does not measure", substring = true) and
            hasText("battery savings", substring = true) and hasText("FPS", substring = true)
        compose.onNode(scopeDisclosure).performScrollTo().assertIsDisplayed()
        val unsupportedMetric = Regex(
            "\\b\\d+(?:[.,]\\d+)?\\s*fps\\b|\\b(?:battery|energy)\\s+savings?\\s*[:=]?\\s*\\d+",
            RegexOption.IGNORE_CASE
        )
        compose.onAllNodes(SemanticsMatcher("numeric battery savings or FPS result") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { unsupportedMetric.containsMatchIn(it.text) } == true
        }).assertCountEquals(0)
    }

    private fun showSession(session: PerformanceSession) {
        compose.setContent {
            MaterialTheme {
                PerformanceContent(
                    model = PerformanceUiModel(session = session),
                    onSelect = {},
                    onStart = {},
                    onStop = {},
                    onExport = {}
                )
            }
        }
    }

    private fun comparisonSession() = PerformanceSession(
        id = 42L,
        app = APP,
        before = phase(listOf(500, 100, 300, 9000, 200), 10_000L),
        after = phase(listOf(400, 900, 150, 450, 350), 30_000L)
    )

    private fun phase(values: List<Long>, elapsed: Long) = MeasurementPhase(
        app = APP,
        buildFingerprint = "recorded/android/build",
        artVersion = "recorded-art-version",
        startedAtMs = 1_701_000_000_000L + elapsed,
        finishedAtMs = 1_701_000_000_100L + elapsed,
        elapsedRealtimeMs = elapsed,
        samples = values.mapIndexed { index, value -> sample(value, 1_701_000_000_000L + elapsed + index) },
        compilerFilter = "speed",
        bootCount = 7
    )

    private fun sample(total: Long, capturedAt: Long) = LaunchSample(
        timing = LaunchTiming(total, null, null, APP.component),
        capturedAtMs = capturedAt,
        before = CONDITIONS,
        after = CONDITIONS,
        rawOutput = coldOutput(total)
    )

    private fun coldOutput(total: Long) = """
        Starting: Intent { cmp=${APP.component} }
        Status: ok
        LaunchState: COLD
        Activity: ${APP.component}
        ThisTime: 420
        TotalTime: $total
        WaitTime: 451
        Complete
    """.trimIndent()

    private companion object {
        val APP = MeasurementApp("com.example.measurement", "Measured app",
            "com.example.measurement/.MainActivity", 7L, 1_700_000_000_000L)
        val CONDITIONS = MeasurementConditions(300, 75, 0, 0, powerSave = false, screenInteractive = true)
    }
}
