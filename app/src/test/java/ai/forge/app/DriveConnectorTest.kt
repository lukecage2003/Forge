package ai.forge.app

import ai.forge.app.drive.DriveConnector
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveConnectorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun listsWithPaginationParsesComicsAndResumesDownloads() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"files":[{"id":"a","name":"Black Panther 008 (2016).cbz","mimeType":"application/zip","md5Checksum":"m1"}],"nextPageToken":"p2"}"""))
            server.enqueue(MockResponse().setBody("""{"files":[{"id":"b","name":"Notes.pdf","mimeType":"application/pdf"}]}"""))
            server.start()
            val drive = DriveConnector({ "jeton" }, tmp.root, baseUrl = server.url("").toString().trimEnd('/'))
            val files = drive.listFolder("dossier")
            assertEquals(listOf("a", "b"), files.map { it.id })
            assertEquals("8", files[0].comicReference.issue)
            assertEquals("a", files[0].comicReference.sourceFileId)
            val first = server.takeRequest()
            assertEquals("Bearer jeton", first.getHeader("Authorization"))
            assertTrue(first.requestUrl!!.queryParameter("q")!!.contains("'dossier' in parents"))
            assertEquals("p2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))

            // Reprise : 3 octets déjà présents → requête Range, réponse 206 ajoutée à la suite.
            java.io.File(tmp.root, "a_m1.part").writeBytes("ABC".toByteArray())
            server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().writeUtf8("DEF")))
            val local = drive.download(files[0])
            assertEquals("ABCDEF", local.readText())
            assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
            // Empreinte identique : aucun nouveau transfert.
            drive.download(files[0])
            assertEquals(3, server.requestCount)
        }
    }
}
