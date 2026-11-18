package ai.forge.market

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import kotlin.test.*

class MarketTest {
    private val now = Instant.parse("2026-11-18T00:00:00Z")
    private fun v(id: String, title: String, views: Long, subs: Long, dur: Int, daysAgo: Long = 10, likes: Long = views / 50, comments: Long = views / 500) =
        VideoStat(id, title, "c$id", "Chaîne $id", now.minusSeconds(daysAgo * 86_400), views, likes, comments, dur, subs)

    @Test fun parsesIsoDurations() {
        assertEquals(59, YouTubeDataClient.parseIsoDuration("PT59S"))
        assertEquals(754, YouTubeDataClient.parseIsoDuration("PT12M34S"))
        assertEquals(3_723, YouTubeDataClient.parseIsoDuration("PT1H2M3S"))
        assertEquals(0, YouTubeDataClient.parseIsoDuration("n'importe quoi"))
    }

    @Test fun findsWhatOutperforms() {
        val vids = listOf(
            v("1", "Pourquoi Venom est-il invincible ?", 900_000, 50_000, 45),
            v("2", "Venom vs Carnage : qui gagne ?", 700_000, 40_000, 50, likes = 40_000, comments = 9_000),
            v("3", "Le secret de Venom ?", 500_000, 30_000, 40),
            v("4", "Venom expliqué", 20_000, 200_000, 900),
            v("5", "Histoire complète de Venom", 30_000, 300_000, 1_500),
            v("6", "Venom résumé", 10_000, 150_000, 1_200),
            v("7", "Analyse Venom", 15_000, 100_000, 1_100),
        )
        val r = MarketAnalyzer.analyze("Venom", vids, now)
        assertEquals("1", r.topOutliers.first().id)
        assertTrue(r.insights.any { it.startsWith("Shorts") && it.contains("×") }, r.insights.toString())
        assertTrue(r.insights.any { it.startsWith("Titres en question") }, r.insights.toString())
        assertTrue(r.summary().contains("données de l'API officielle"))
        assertTrue(r.summary().contains("Échantillon réduit"))
        assertTrue(MarketAnalyzer.analyze("x", emptyList()).insights.single().startsWith("Aucune vidéo"))
    }

    @Test fun clientChainsSearchVideosAndChannels() = runTest {
        MockWebServer().use { s ->
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/youtube/v3/search" -> MockResponse().setBody("""{"items":[{"id":{"videoId":"a"}},{"id":{"videoId":"b"}}]}""")
                    "/youtube/v3/videos" -> MockResponse().setBody("""{"items":[
                        {"id":"a","snippet":{"title":"Venom ?","channelId":"c1","channelTitle":"C1","publishedAt":"2026-11-01T00:00:00Z"},"statistics":{"viewCount":"1000","likeCount":"10"},"contentDetails":{"duration":"PT45S"}},
                        {"id":"b","snippet":{"title":"Venom long","channelId":"c1","channelTitle":"C1","publishedAt":"2026-10-01T00:00:00Z"},"statistics":{"viewCount":"50"},"contentDetails":{"duration":"PT12M"}}]}""")
                    "/youtube/v3/channels" -> MockResponse().setBody("""{"items":[{"id":"c1","statistics":{"subscriberCount":"2000"}}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            s.start()
            val vids = YouTubeDataClient("CLE", baseUrl = s.url("/youtube/v3").toString().trimEnd('/')).topVideos("Venom", now.minusSeconds(86_400 * 90))
            assertEquals(listOf("a", "b"), vids.map { it.id })
            assertTrue(vids[0].isShort); assertFalse(vids[1].isShort)
            assertEquals(2_000, vids[0].channelSubscribers)
            assertEquals("CLE", s.takeRequest().requestUrl!!.queryParameter("key"))
        }
    }

    @Test fun apiErrorsAreReadable() = runTest {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"The request cannot be completed because you have exceeded your quota."}}"""))
            s.start()
            val e = assertFails { YouTubeDataClient("k", baseUrl = s.url("/y").toString().trimEnd('/')).topVideos("x", now) }
            assertTrue(e.message!!.contains("quota"))
        }
    }
}
