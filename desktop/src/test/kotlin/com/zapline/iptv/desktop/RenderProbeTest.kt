package com.zapline.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File

class RenderProbeTest {
    @Test fun renders() {
        val scene = ImageComposeScene(1280, 360, Density(1f)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF15131F))) { Text("Zapline", color = Color.White) }
        }
        val img = scene.render()
        File("build/probe.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
