package eu.kanade.tachiyomi.ui.reader.viewer.pager

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// KMK -->
/**
 * An orthographic sheet that can be deformed two ways, and the two compose.
 *
 * Cylinder mode wraps the sheet around a finger-controlled cylinder — the single-page curl. Book
 * mode pivots the whole sheet rigidly about [hingeX], the spine of a double-page spread, carrying
 * the cylinder deformation with it. Both deformations rotate about vertical axes, so the combined
 * surface rotation is just their sum, and the crease always sits right of the hinge so the page
 * near the spine stays flat and the facing page is untouched.
 */
internal class CurlGeometry {
    /** Cylinder radius of the curl; 0 leaves the sheet rigid. */
    var radius = 0f
        private set
    var normalX = 1f
        private set
    var normalY = 0f
        private set

    /** Depth range the renderer sorts and shades against. */
    val depthScale: Float
        get() = if (bookMode) BOOK_DEPTH_SCALE else radius

    /** Spread of the soft cast shadow. */
    val shadowScale: Float
        get() = if (bookMode) BOOK_SHADOW_SCALE else radius

    private var creaseX = 0f
    private var creaseY = 0f
    private var bookMode = false
    private var hingeX = 0f
    private var tilt = 0f
    private var curlStrength = 1f

    /**
     * Cylinder curl only. [originX] is the outer edge of the sheet being turned: the right edge of
     * the view for a single page, or the spine in a double-page spread.
     */
    fun configure(originX: Float, anchorY: Float, fingerX: Float, fingerY: Float) {
        bookMode = false
        configureCylinder(originX, anchorY, fingerX, fingerY)
    }

    /**
     * Book turn: the sheet pivots about [hingeX] through [tilt] radians, carrying the finger-driven
     * curl configured from [originX], [anchorY] and the finger position. [curlStrength] scales that
     * curl, so the turn can relax the bend to nothing as it completes and hand a flat page over.
     */
    fun configureBook(
        hingeX: Float,
        tilt: Float,
        curlStrength: Float,
        originX: Float,
        anchorY: Float,
        fingerX: Float,
        fingerY: Float,
    ) {
        configureCylinder(originX, anchorY, fingerX, fingerY)
        bookMode = true
        this.hingeX = hingeX
        this.tilt = tilt
        this.curlStrength = curlStrength
        // Never let the crease cross the spine, so the turning page stays attached to the hinge.
        if (creaseX < hingeX) creaseX = hingeX
        // Keep the bend well short of half a turn. At half a turn the far edge folds over the
        // crease and its corners tuck underneath; stopping short of 90 degrees keeps the whole
        // sheet - corners included - facing the reader for the entire turn.
        val sheet = originX - creaseX
        if (sheet > 0f) radius = maxOf(radius, sheet / BOOK_MAX_WRAP)
    }

    private fun configureCylinder(originX: Float, anchorY: Float, fingerX: Float, fingerY: Float) {
        val dx = originX - fingerX
        val dy = anchorY - fingerY
        val distance = hypot(dx, dy)
        if (distance < 0.001f) {
            radius = 0f
            normalX = 1f
            normalY = 0f
            creaseX = originX
            creaseY = anchorY
            return
        }
        normalX = dx / distance
        normalY = dy / distance
        radius = minOf(originX * 0.065f, distance * 0.16f)
        // Offset the cylinder's start so the folded edge lands exactly on the finger.
        val halfArc = (PI * radius * 0.5).toFloat()
        creaseX = (originX + fingerX) * 0.5f - normalX * halfArc
        creaseY = (anchorY + fingerY) * 0.5f - normalY * halfArc
    }

    /** Writes x, y, height above the paper and the surface normal's z component. */
    fun project(x: Float, y: Float, result: FloatArray, offset: Int = 0) {
        if (bookMode) {
            // Only the page on the hinge's far side turns; the facing page must stay put. The
            // comparison is strict so the vertices sitting exactly on the spine belong to the
            // turning page. With them on the facing side, the triangles straddling the spine pick
            // up mixed normals, disagree with their neighbours and sawtooth along the seam.
            if (x < hingeX) {
                result[offset] = x
                result[offset + 1] = y
                result[offset + 2] = 0f
                result[offset + 3] = 1f
                return
            }
            val distance = (x - creaseX) * normalX + (y - creaseY) * normalY
            var curl = 0f
            var height = 0f
            var surface = 0f
            if (radius > 0f && distance > 0f) {
                // Capped at half a turn: past that a real page folds flat over the crease instead of
                // rolling on, which is what used to drag the sheet back across its own artwork.
                val eased = (distance / radius).coerceIn(0f, PI.toFloat())
                // Scaling every part by [curlStrength] relaxes the bend to nothing at strength 0,
                // leaving just the spine pivot, so the turn can hand over a perfectly flat page.
                surface = eased * curlStrength
                curl = (radius * sin(eased) - distance) * curlStrength
                height = radius * (1f - cos(eased)) * curlStrength
            }
            // Curl inside the page's own frame, then pivot that frame about the spine.
            val fromHinge = x + normalX * curl - hingeX
            val cosTilt = cos(tilt)
            val sinTilt = sin(tilt)
            result[offset] = hingeX + fromHinge * cosTilt - height * sinTilt
            result[offset + 1] = y + normalY * curl
            result[offset + 2] = fromHinge * sinTilt + height * cosTilt
            result[offset + 3] = cos(surface + tilt)
            return
        }

        val distance = (x - creaseX) * normalX + (y - creaseY) * normalY
        var displacement = 0f
        var height = 0f
        var normalZ = 1f
        if (radius > 0f && distance > 0f) {
            val angle = minOf(distance / radius, PI.toFloat())
            val arcLength = PI.toFloat() * radius
            val projectedDistance = if (distance < arcLength) {
                radius * sin(angle)
            } else {
                arcLength - distance
            }
            displacement = projectedDistance - distance
            normalZ = cos(angle)
            height = radius * (1f - normalZ)
        }
        result[offset] = x + normalX * displacement
        result[offset + 1] = y + normalY * displacement
        result[offset + 2] = height
        result[offset + 3] = normalZ
    }

    private companion object {
        /**
         * Depth scale for a book turn: small enough to keep the cast shadow tight, while the
         * turning page's own depth still saturates the buckets so it sorts above the flat page.
         */
        const val BOOK_DEPTH_SCALE = 40f
        const val BOOK_SHADOW_SCALE = 24f

        /**
         * Largest angle, in degrees, the sheet may bend through between the crease and its outer
         * edge. Lower keeps the page flatter and its corners visible; higher gives a rounder curl.
         */
        const val BOOK_MAX_WRAP_DEGREES = 82.5f

        val BOOK_MAX_WRAP = (BOOK_MAX_WRAP_DEGREES * PI / 180.0).toFloat()
    }
}
// KMK <--
