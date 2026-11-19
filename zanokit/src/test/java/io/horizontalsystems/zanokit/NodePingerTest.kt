package io.horizontalsystems.zanokit

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NodePingerTest {

    private fun getInfo(status: String = "OK", networkState: Int = 2, height: Long = 3908750) =
        """{"id":0,"jsonrpc":"2.0","result":{"daemon_network_state":$networkState,"height":$height,"status":"$status"}}"""

    private val servers = mutableListOf<MockWebServer>()

    private fun server(response: MockResponse): String {
        val server = MockWebServer().apply {
            enqueue(response)
            start()
        }
        servers.add(server)
        return server.url("/").toString()
    }

    @After
    fun tearDown() {
        servers.forEach { it.shutdown() }
    }

    // What the answer says

    @Test
    fun readyDaemonReportsItsHeight() {
        assertEquals(3908750L, NodePinger.syncedHeight(getInfo()))
    }

    // A daemon still downloading blocks answers, but a wallet cannot sync from it
    @Test
    fun daemonThatIsStillSynchronizingIsNotReady() {
        assertNull(NodePinger.syncedHeight(getInfo(networkState = 1)))
    }

    @Test
    fun busyDaemonIsNotReady() {
        assertNull(NodePinger.syncedHeight(getInfo(status = "BUSY")))
    }

    @Test
    fun daemonWithoutBlocksIsNotReady() {
        assertNull(NodePinger.syncedHeight(getInfo(height = 0)))
    }

    @Test
    fun errorAnswerIsNotReady() {
        assertNull(NodePinger.syncedHeight("""{"error":{"code":-32601,"message":"Method not found"},"id":0,"jsonrpc":"2.0"}"""))
    }

    @Test
    fun answerThatIsNotFromADaemonIsNotReady() {
        assertNull(NodePinger.syncedHeight("<html>502 Bad Gateway</html>"))
        assertNull(NodePinger.syncedHeight(""))
    }

    // Over HTTP

    @Test
    fun everyDaemonGetsItsOwnResultInTheOrderAsked() = runBlocking {
        val ready = server(MockResponse().setBody(getInfo(height = 100)))
        val failing = server(MockResponse().setResponseCode(500))
        val syncing = server(MockResponse().setBody(getInfo(networkState = 1)))

        val results = NodePinger(timeoutMs = 2_000).ping(listOf(ready, failing, syncing))

        assertEquals(listOf(ready, failing, syncing), results.map { it.daemonAddress })
        assertEquals(listOf(true, false, false), results.map { it.isValid })
        assertEquals(100L, results[0].height)
        assertTrue(results[0].responseTime < 2_000)
        assertEquals(Double.MAX_VALUE, results[1].responseTime, 0.0)
        assertEquals(0L, results[2].height)
    }

    @Test
    fun getInfoIsAskedAtTheDaemonsJsonRpcPath() = runBlocking {
        val address = server(MockResponse().setBody(getInfo()))

        // The app stores daemon addresses with and without a trailing slash
        NodePinger().ping(listOf(address.trimEnd('/')))

        val request = servers.single().takeRequest()
        assertEquals("/json_rpc", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().contains("\"method\":\"getinfo\""))
    }

    // The engine takes an address without a scheme, and so must the ping
    @Test
    fun addressWithoutASchemeIsAsked() = runBlocking {
        val address = server(MockResponse().setBody(getInfo()))

        val result = NodePinger().ping(listOf(address.removePrefix("http://").trimEnd('/'))).single()

        assertTrue(result.isValid)
        assertEquals("/json_rpc", servers.single().takeRequest().path)
    }

    @Test
    fun addressIsAskedWhereTheEngineConnects() {
        assertEquals("https://zano.unstoppable.money:443/json_rpc", NodePinger.url("https://zano.unstoppable.money:443"))
        assertEquals("https://node.zano.org/json_rpc", NodePinger.url("https://node.zano.org/"))
        assertEquals("http://10.0.2.2:18547/json_rpc", NodePinger.url("http://10.0.2.2:18547"))
        assertEquals("http://node.zano.org:11211/json_rpc", NodePinger.url("node.zano.org:11211"))
        assertEquals("http://node.zano.org:8081/json_rpc", NodePinger.url("node.zano.org"))
    }

    // Headers can arrive at once while the answer itself is still being produced
    @Test
    fun responseTimeCoversTheAnswerNotOnlyItsHeaders() = runBlocking {
        val slowBody = server(MockResponse().setBody(getInfo()).setBodyDelay(400, TimeUnit.MILLISECONDS))

        val result = NodePinger(timeoutMs = 3_000).ping(listOf(slowBody)).single()

        assertTrue(result.isValid)
        assertTrue("reported ${result.responseTime} ms", result.responseTime >= 350)
    }

    @Test
    fun daemonThatNeverAnswersIsGivenUpOnAfterTheTimeout() = runBlocking {
        val silent = server(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val startedAt = System.nanoTime()
        val result = NodePinger(timeoutMs = 300).ping(listOf(silent)).single()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertFalse(result.isValid)
        assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
    }

    @Test
    fun addressThatCannotBeReachedIsNotValid() = runBlocking {
        val results = NodePinger(timeoutMs = 2_000).ping(listOf("http://127.0.0.1:1", "not a url"))

        assertEquals(listOf(false, false), results.map { it.isValid })
    }
}
