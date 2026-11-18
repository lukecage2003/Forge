package ai.forge.models

import ai.forge.core.model.ChatMessage
import ai.forge.core.model.Role
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DownloadTest {
    private val content = ByteArray(10_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    @Test fun resumesAfterInterruptionAndVerifiesIntegrity() = runTest {
        MockWebServer().use { server ->
            server.start()
            val model = DownloadableModel("m", "Test", "", server.url("/m.task").toString(), "m.task", content.size.toLong(), sha, "MIT", 100, 2, 1024)
            val dir = createTempDirectory("models").toFile()
            // Première tentative coupée à 4 000 octets.
            java.io.File(dir, "m.task.part").writeBytes(content.copyOfRange(0, 4_000))
            server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(content.copyOfRange(4_000, content.size))))
            val r = ModelDownloader().download(model, dir)
            assertIs<ModelDownloader.Result.Ready>(r)
            assertContentEquals(content, r.file.readBytes())
            assertEquals("bytes=4000-", server.takeRequest().getHeader("Range"))
            // Déjà présent : aucun nouvel appel.
            assertIs<ModelDownloader.Result.Ready>(ModelDownloader().download(model, dir))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun corruptedFileIsRejectedAndDeleted() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bad = content.copyOf().also { it[5] = 99 }
            server.enqueue(MockResponse().setBody(Buffer().write(bad)))
            val model = DownloadableModel("m", "Test", "", server.url("/m.task").toString(), "m.task", content.size.toLong(), sha, "MIT", 100, 2, 1024)
            val dir = createTempDirectory("models2").toFile()
            val r = ModelDownloader().download(model, dir)
            assertIs<ModelDownloader.Result.Failed>(r)
            assertTrue(r.reason.contains("corrompu"))
            assertFalse(java.io.File(dir, "m.task").exists()); assertFalse(java.io.File(dir, "m.task.part").exists())
        }
    }

    @Test fun templatesMatchModelFamilies() {
        val msgs = listOf(ChatMessage(Role.SYSTEM, "Tu es Forge."), ChatMessage(Role.USER, "Salut"))
        assertEquals(PromptTemplate.CHATML, PromptTemplate.forModelFile(DownloadableModel.QWEN25_1_5B.fileName))
        assertEquals(LocalRuntime.LITERT_LM, DownloadableModel.RECOMMENDED.runtime)
        assertEquals(LocalRuntime.MEDIAPIPE, DownloadableModel.QWEN25_1_5B.runtime)
        assertTrue(DownloadableModel.CATALOG.all { it.sha256.length == 64 && it.url.endsWith(it.fileName) && it.license == "Apache 2.0" })
        assertEquals(PromptTemplate.GEMMA, PromptTemplate.forModelFile("gemma3-1b-it-int4.task"))
        assertEquals("<|im_start|>system\nTu es Forge.<|im_end|>\n<|im_start|>user\nSalut<|im_end|>\n<|im_start|>assistant\n",
            PromptTemplate.CHATML.render(msgs))
        assertTrue(PromptTemplate.GEMMA.render(msgs).startsWith("<start_of_turn>user\nTu es Forge.\n\nSalut"))
    }
}
