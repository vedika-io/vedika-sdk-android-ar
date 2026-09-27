package io.vedika.sdk.ar

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Shared-fixture plumbing for [RoomCaptureGeometryTest] and
 * [RoomCaptureModelTest]: locates `sdks/fixtures/vastu-room-capture` (its `.json` files)
 * from within a monorepo checkout (same walk-up-and-skip pattern as
 * `VastuResponseFixtureTest` in `sdks/android`, for the same reason — a
 * published standalone package does not carry the fixture directory), parses
 * a fixture's `session` object into [RoomCaptureSession], and asserts a built
 * [io.vedika.sdk.VastuRoomCapture] equals a fixture's `expected.capture`
 * within 1e-6 for every numeric field.
 */
object RoomCaptureFixtures {
    private const val TOLERANCE = 1e-6

    fun monorepoRoot(): File? = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .firstOrNull { File(it, "sdks/fixtures/vastu-room-capture").isDirectory }

    fun load(root: File): List<JSONObject> =
        File(root, "sdks/fixtures/vastu-room-capture")
            .listFiles { f -> f.name.endsWith(".json") }!!
            .sortedBy { it.name }
            .map { JSONObject(it.readText()) }

    private fun vec3(arr: JSONArray): Vec3 = Vec3(arr.getDouble(0), arr.getDouble(1), arr.getDouble(2))

    fun parseSession(session: JSONObject): RoomCaptureSession {
        val device = session.getJSONObject("device")
        val north = session.getJSONObject("north")
        val outline = session.getJSONObject("outline")
        val quality = session.getJSONObject("quality")
        return RoomCaptureSession(
            captureId = session.getString("captureId"),
            capturedAtEpoch = session.getLong("capturedAtEpoch"),
            device = RoomCaptureDeviceInput(
                platform = device.getString("platform"),
                method = device.getString("method"),
                depth = device.getString("depth"),
            ),
            headingFrame = session.getString("headingFrame"),
            north = RoomCaptureNorthInput(
                referenceFrame = north.getString("referenceFrame"),
                headingSource = north.getString("headingSource"),
                declinationDeg = if (north.isNull("declinationDeg")) null else north.getDouble("declinationDeg"),
                declinationProvenance = north.getString("declinationProvenance"),
            ),
            headingSamples = session.getJSONArray("headingSamples").let { arr ->
                List(arr.length()) { i ->
                    val s = arr.getJSONObject(i)
                    RoomCaptureHeadingSample(s.getDouble("headingDeg"), vec3(s.getJSONArray("forward")))
                }
            },
            outline = RoomCaptureOutlineInput(
                source = outline.getString("source"),
                worldCorners = outline.getJSONArray("worldCorners").let { arr -> List(arr.length()) { vec3(arr.getJSONArray(it)) } },
                closingTap = if (outline.has("closingTap") && !outline.isNull("closingTap")) vec3(outline.getJSONArray("closingTap")) else null,
            ),
            rooms = session.getJSONArray("rooms").let { arr ->
                List(arr.length()) { i ->
                    val r = arr.getJSONObject(i)
                    RoomCaptureRoomInput(
                        id = r.getString("id"),
                        label = if (r.isNull("label")) null else r.getString("label"),
                        labelSource = r.getString("labelSource"),
                        floorIndex = r.getInt("floorIndex"),
                        heightM = if (!r.has("heightM") || r.isNull("heightM")) null else r.getDouble("heightM"),
                        worldCorners = r.getJSONArray("worldCorners").let { a -> List(a.length()) { vec3(a.getJSONArray(it)) } },
                        closingTap = if (r.has("closingTap") && !r.isNull("closingTap")) vec3(r.getJSONArray("closingTap")) else null,
                        openings = if (!r.has("openings")) emptyList() else r.getJSONArray("openings").let { arr2 ->
                            List(arr2.length()) { j ->
                                val o = arr2.getJSONObject(j)
                                RoomCaptureOpeningInput(
                                    kind = o.getString("kind"),
                                    centerWorld = vec3(o.getJSONArray("centerWorld")),
                                    widthM = o.getDouble("widthM"),
                                    heightM = if (!o.has("heightM") || o.isNull("heightM")) null else o.getDouble("heightM"),
                                    confidence = o.getString("confidence"),
                                )
                            }
                        },
                    )
                }
            },
            quality = RoomCaptureQualityInput(
                pointCloudDensity = if (quality.isNull("pointCloudDensity")) null else quality.getDouble("pointCloudDensity"),
                pointCloudDensityBasis = quality.getString("pointCloudDensityBasis"),
                coveragePercent = if (quality.isNull("coveragePercent")) null else quality.getDouble("coveragePercent"),
                scanDurationSec = if (quality.isNull("scanDurationSec")) null else quality.getDouble("scanDurationSec"),
                scannedAreaM2 = if (quality.isNull("scannedAreaM2")) null else quality.getDouble("scannedAreaM2"),
                expectedRoomCount = if (quality.isNull("expectedRoomCount")) null else quality.getInt("expectedRoomCount"),
                gpsConfidence = if (quality.isNull("gpsConfidence")) null else quality.getDouble("gpsConfidence"),
            ),
        )
    }

    /** Deep-compares a [toMap]-encoded capture against a fixture's expected JSON, numeric fields within 1e-6. */
    fun assertMatches(actual: Any?, expected: Any?, path: String = "$") {
        when {
            expected == null || expected == JSONObject.NULL -> assertTrue("$path: expected null, got $actual", actual == null)
            expected is JSONObject -> {
                assertTrue("$path: expected a Map, got $actual", actual is Map<*, *>)
                val map = actual as Map<*, *>
                val actualKeys = map.keys.map { it.toString() }.toSet()
                // VastuRoomCapture's toMap() (see VastuRequest.toMap() in VastuService.kt)
                // omits a field entirely when its value is null, rather than emitting an
                // explicit JSON null — a request-serialization convention shared by every
                // *Request data class in this SDK. The fixture's `expected.capture` instead
                // spells out every schema property, nulls included. Both represent the same
                // capture: an omitted key must correspond to an explicit null in the fixture,
                // never to an unexpected extra or missing non-null field.
                assertTrue("$path: extra keys not in fixture ${actualKeys - expected.keySet()}", expected.keySet().containsAll(actualKeys))
                for (key in expected.keySet()) {
                    val expectedValue = expected.get(key).let { if (it == JSONObject.NULL) null else it }
                    if (key !in actualKeys) {
                        assertTrue("$path.$key: missing from the built capture but fixture expects $expectedValue", expectedValue == null)
                        continue
                    }
                    assertMatches(map[key], expectedValue, "$path.$key")
                }
            }
            expected is JSONArray -> {
                assertTrue("$path: expected a List, got $actual", actual is List<*>)
                val list = actual as List<*>
                assertEquals("$path: length", expected.length(), list.size)
                for (i in 0 until expected.length()) {
                    assertMatches(list[i], expected.get(i).let { if (it == JSONObject.NULL) null else it }, "$path[$i]")
                }
            }
            expected is Number -> {
                assertTrue("$path: expected a Number, got $actual", actual is Number)
                val diff = kotlin.math.abs(expected.toDouble() - (actual as Number).toDouble())
                assertTrue("$path: expected ${expected.toDouble()}, got ${actual.toDouble()} (diff $diff)", diff <= TOLERANCE)
            }
            expected is Boolean -> assertEquals("$path", expected, actual)
            expected is String -> assertEquals("$path", expected, actual)
            else -> fail("$path: unhandled fixture value type ${expected::class}")
        }
    }
}
