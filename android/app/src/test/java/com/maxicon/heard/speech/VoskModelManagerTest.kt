package com.maxicon.heard.speech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class VoskModelManagerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("vosk_test")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun statusForDir_emptyFilesDir_returnsNotDownloaded() {
        assertEquals(VoskModelManager.Status.NOT_DOWNLOADED, VoskModelManager.statusForDir(tempDir))
    }

    @Test
    fun statusForDir_modelDirMissing_returnsNotDownloaded() {
        File(tempDir, "vosk_models").mkdirs()
        assertEquals(VoskModelManager.Status.NOT_DOWNLOADED, VoskModelManager.statusForDir(tempDir))
    }

    @Test
    fun statusForDir_modelDirExistsButEmpty_returnsNotDownloaded() {
        File(File(tempDir, "vosk_models"), "vosk-model-small-en-us-0.15").mkdirs()
        assertEquals(VoskModelManager.Status.NOT_DOWNLOADED, VoskModelManager.statusForDir(tempDir))
    }

    @Test
    fun statusForDir_modelDirHasFiles_returnsReady() {
        val modelDir = File(File(tempDir, "vosk_models"), "vosk-model-small-en-us-0.15")
        modelDir.mkdirs()
        File(modelDir, "am").mkdirs()
        File(modelDir, "conf").mkdir()
        File(modelDir, "graph").mkdir()
        File(File(modelDir, "am"), "final.mdl").writeText("stub")
        assertEquals(VoskModelManager.Status.READY, VoskModelManager.statusForDir(tempDir))
    }

    @Test
    fun deleteFromDir_removesModelDirectory() {
        val modelDir = File(File(tempDir, "vosk_models"), "vosk-model-small-en-us-0.15")
        modelDir.mkdirs()
        File(modelDir, "am").mkdir()
        File(File(modelDir, "am"), "final.mdl").writeText("stub")

        assertEquals(VoskModelManager.Status.READY, VoskModelManager.statusForDir(tempDir))
        VoskModelManager.deleteFromDir(tempDir)
        assertEquals(VoskModelManager.Status.NOT_DOWNLOADED, VoskModelManager.statusForDir(tempDir))
    }

    @Test
    fun deleteFromDir_noModelPresent_doesNotThrow() {
        VoskModelManager.deleteFromDir(tempDir)
        assertEquals(VoskModelManager.Status.NOT_DOWNLOADED, VoskModelManager.statusForDir(tempDir))
    }

    @Test
    fun statusForDir_readyThenDeleted_returnsNotDownloaded() {
        val modelDir = File(File(tempDir, "vosk_models"), "vosk-model-small-en-us-0.15")
        modelDir.mkdirs()
        File(modelDir, "final.mdl").writeText("stub")

        assertEquals(VoskModelManager.Status.READY, VoskModelManager.statusForDir(tempDir))
        VoskModelManager.deleteFromDir(tempDir)
        assertEquals(VoskModelManager.Status.NOT_DOWNLOADED, VoskModelManager.statusForDir(tempDir))
    }
}
