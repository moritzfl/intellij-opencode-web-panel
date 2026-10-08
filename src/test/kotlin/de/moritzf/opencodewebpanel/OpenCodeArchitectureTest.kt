package de.moritzf.opencodewebpanel

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeArchitectureTest {
    @Test
    fun lowerLayersDoNotDependOnFeaturesOrPanelGlue() {
        val root = Path.of("src/main/kotlin/de/moritzf/opencodewebpanel")
        assertTrue("Source root unavailable: $root", Files.isDirectory(root))
        val forbidden =
            mapOf(
                "server" to setOf("browser", "features", "toolWindow"),
                "browser" to setOf("features", "toolWindow"),
                "features" to setOf("toolWindow"),
            )
        // Includes fully qualified references as well as imports. Settings is intentionally shared;
        // its Apply/restart adapter is the documented exception reaching back into toolWindow.
        val reference =
            Regex(
                "de\\.moritzf\\.opencodewebpanel\\.(server|browser|features|toolWindow|settings)\\."
            )
        val violations = mutableListOf<String>()
        Files.walk(root).use { paths ->
            paths
                .filter { it.toString().endsWith(".kt") }
                .forEach { file ->
                    val sourceLayer = root.relativize(file).getName(0).toString()
                    val denied = forbidden[sourceLayer].orEmpty()
                    Files.readAllLines(file).forEachIndexed { index, line ->
                        val trimmed = line.trimStart()
                        if (
                            trimmed.startsWith("//") ||
                                trimmed.startsWith("/*") ||
                                trimmed.startsWith("*")
                        )
                            return@forEachIndexed
                        reference.findAll(line).forEach { match ->
                            val target = match.groupValues[1]
                            if (target in denied)
                                violations +=
                                    "${root.relativize(file)}:${index + 1}: $sourceLayer -> $target"
                        }
                    }
                }
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }
}
