package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.renderComposeScene
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

private const val SAMPLES = 50

/** An off-screen render of a composable: the written PNG and the decoded image. */
class Screenshot(private val file: Path, private val image: Image, private val width: Int, private val height: Int) {
    /** The colour (ARGB) of the pixel at [x], [y]. */
    fun pixel(x: Int, y: Int): Int {
        val pixmap = checkNotNull(image.peekPixels()) { "Pixels of ${file.fileName} are not readable." }
        return pixmap.getColor(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
    }

    /** A pixel near the bottom-left corner, which shows the window background in the demo screens. */
    fun backgroundPixel(): Int = pixel(BACKGROUND_INSET, height - BACKGROUND_INSET)

    /** The distinct colours of a regular grid of sampled pixels. */
    fun sampledColors(): Set<Int> = (0 until SAMPLES).flatMap { column ->
        (0 until SAMPLES).map { row -> pixel(column * width / SAMPLES, row * height / SAMPLES) }
    }.toSet()

    private companion object {
        const val BACKGROUND_INSET = 5
    }
}

/**
 * Renders [content] off-screen, writes it to `build/screenshots/<name>.png` and checks that it is not blank,
 * meaning the sampled pixels show more than one distinct colour.
 */
fun renderScreenshot(name: String, width: Int = 720, height: Int = 760, content: @Composable () -> Unit): Screenshot {
    val image = renderComposeScene(width = width, height = height, content = content)
    val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Can't encode $name as PNG." }.bytes
    val directory = Files.createDirectories(Path.of("build", "screenshots"))
    val screenshot = Screenshot(
        file = Files.write(directory.resolve("$name.png"), png),
        image = image,
        width = width,
        height = height,
    )
    assertTrue(screenshot.sampledColors().size > 1, "$name looks blank")
    return screenshot
}
