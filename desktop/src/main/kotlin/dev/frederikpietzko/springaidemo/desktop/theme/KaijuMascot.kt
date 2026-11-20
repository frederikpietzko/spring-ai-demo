package dev.frederikpietzko.springaidemo.desktop.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * The Kaiju Insurance mascot as a 16 x 16 pixel grid, one character per pixel: `.` transparent, `D` outline,
 * `G` body, `L` belly, `W` eye, `K` pupil and mouth, `T` tooth, `S` horn.
 */
private val KAIJU_PIXELS: List<String> = [
    "................",
    "...S........S...",
    "...SS......SS...",
    "....DDDDDDDD....",
    "...DGGGGGGGGD...",
    "..DGGGGGGGGGGD..",
    "..DGWKGGGGWKGD..",
    "..DGWWGGGGWWGD..",
    "..DGGGGGGGGGGD..",
    "..DGGKTKKTKGGD..",
    "...DGGGGGGGGD...",
    "..DGLLLLLLLLGD..",
    ".DGDLLLLLLLLDGD.",
    ".DD.DLLLLLLD.DD.",
    "....DGGDDGGD....",
    "....DDD..DDD....",
]

private const val GRID = 16

private fun KaijuPalette.colorOf(pixel: Char): Color? = when (pixel) {
    'D' -> outline
    'G' -> body
    'L' -> belly
    'W' -> eye
    'K' -> pupil
    'T' -> tooth
    'S' -> horn
    else -> null
}

/** Draws the mascot into [size], each grid cell snapped to whole pixels so it stays crisp at any scale. */
private fun DrawScope.drawKaiju(size: Size, palette: KaijuPalette) {
    KAIJU_PIXELS.forEachIndexed { y, row ->
        row.forEachIndexed { x, pixel ->
            val color = palette.colorOf(pixel) ?: return@forEachIndexed
            val left = (x * size.width / GRID).roundToInt().toFloat()
            val top = (y * size.height / GRID).roundToInt().toFloat()
            val right = ((x + 1) * size.width / GRID).roundToInt().toFloat()
            val bottom = ((y + 1) * size.height / GRID).roundToInt().toFloat()
            drawRect(color, Offset(left, top), Size(right - left, bottom - top))
        }
    }
}

/** The mascot as a [Painter], e.g. for the window icon. */
class KaijuPainter(private val palette: KaijuPalette = DefaultKaijuPalette) : Painter() {
    override val intrinsicSize: Size = Size(ICON_SIZE, ICON_SIZE)

    override fun DrawScope.onDraw() = drawKaiju(size, palette)

    private companion object {
        const val ICON_SIZE = 256f
    }
}

/** The mascot, scaled to the [modifier]'s size. */
@Composable
fun KaijuMascot(modifier: Modifier = Modifier, palette: KaijuPalette = DefaultKaijuPalette) {
    Canvas(modifier) { drawKaiju(size, palette) }
}

/** The mascot as an AWT image of [size] x [size] pixels, e.g. for the macOS dock icon. */
fun kaijuIconImage(size: Int, palette: KaijuPalette = DefaultKaijuPalette): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    KAIJU_PIXELS.forEachIndexed { y, row ->
        row.forEachIndexed { x, pixel ->
            val color = palette.colorOf(pixel) ?: return@forEachIndexed
            graphics.color = java.awt.Color(color.toArgb(), true)
            val left = x * size / GRID
            val top = y * size / GRID
            graphics.fillRect(left, top, (x + 1) * size / GRID - left, (y + 1) * size / GRID - top)
        }
    }
    graphics.dispose()
    return image
}
