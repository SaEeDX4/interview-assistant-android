package com.interviewassistant.app

import com.interviewassistant.client.CloudOptions
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DC-212 regression pin: the cloud.json that actually ships in the app must,
 * parsed by the mirrored loader, yield a configured service. The device
 * smoke test of 2026-11-25 found the app reporting "service not configured"
 * on a real phone because the bundled file never reached the reader; this
 * test pins the shipped asset half of that chain (the extraction half is
 * platform glue in Workspace.kt, exercised on device).
 */
class ShippedCloudConfigTests {

    companion object {
        /** Repository root (settings.gradle.kts), or null outside a source checkout. */
        private fun repoRoot(): File? {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            return null
        }
    }

    @Test
    fun shippedCloudJsonParsesToAConfiguredService() {
        val root = repoRoot() ?: return
        val asset = File(root, "app/src/main/assets/cloud.json")
        assertTrue("app/src/main/assets/cloud.json is missing", asset.isFile)
        val options = CloudOptions.fromJson(asset.readText(Charsets.UTF_8))
        assertTrue(
            "the shipped cloud.json does not yield a configured service (apiBaseUrl='${options.apiBaseUrl}')",
            options.isConfigured,
        )
        assertTrue("the shipped apiBaseUrl must be https", options.apiBaseUrl.startsWith("https://"))
    }
}
