package dev.leo.manager.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelPickerTest : ModelPickerCases() {
    override fun captureReasoning() {
        val file = java.io.File("build/reports/model-picker/reasoning.png")
        file.parentFile!!.mkdirs()
        compose.onNodeWithTag("model-settings-sheet").captureToImage().asAndroidBitmap().let {
            bitmap ->
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
