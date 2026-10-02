package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurlGeometryTest {
    private val geometry = CurlGeometry()
    private val point = FloatArray(4)

    @Test
    fun `an unpulled page stays flat`() {
        geometry.configure(1000f, 600f, 1000f, 600f)
        for (x in 0..1000 step 100) {
            for (y in 0..1500 step 100) {
                geometry.project(x.toFloat(), y.toFloat(), point)
                assertEquals(x.toFloat(), point[0], 0.001f)
                assertEquals(y.toFloat(), point[1], 0.001f)
                assertEquals(0f, point[2], 0.001f)
                assertEquals(1f, point[3], 0.001f)
            }
        }
    }

    @Test
    fun `the grabbed edge lands on the finger including diagonal pulls`() {
        for (finger in listOf(950f to 600f, 400f to 800f, -500f to 100f, 200f to 1400f)) {
            geometry.configure(1000f, 600f, finger.first, finger.second)
            geometry.project(1000f, 600f, point)
            assertEquals(finger.first, point[0], 0.002f)
            assertEquals(finger.second, point[1], 0.002f)
            assertEquals(2f * geometry.radius, point[2], 0.002f)
            assertEquals(-1f, point[3], 0.002f)
        }
    }

    @Test
    fun `a completed turn moves the whole sheet out of the viewport`() {
        geometry.configure(1000f, 600f, -1200f, 600f)
        for (x in 0..1000 step 20) {
            for (y in 0..1500 step 100) {
                geometry.project(x.toFloat(), y.toFloat(), point)
                assertTrue(point[0] < 0f)
            }
        }
    }

    @Test
    fun `cylinder deformation preserves distances along the fold axis`() {
        geometry.configure(1000f, 600f, 200f, 900f)
        for (x in 0..1000 step 100) {
            for (y in 0..1500 step 100) {
                geometry.project(x.toFloat(), y.toFloat(), point)
                val original = -x * geometry.normalY + y * geometry.normalX
                val projected = -point[0] * geometry.normalY + point[1] * geometry.normalX
                assertEquals(original, projected, 0.002f)
                assertTrue(point.all { it.isFinite() })
                assertTrue(point[2] >= 0f && point[2] <= 2f * geometry.radius + 0.001f)
            }
        }
    }
}
