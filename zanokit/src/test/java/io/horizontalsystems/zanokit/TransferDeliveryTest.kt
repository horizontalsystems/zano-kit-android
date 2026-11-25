package io.horizontalsystems.zanokit

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TransferDeliveryTest {

    private val hash = "e44e5e29" + "0".repeat(48) + "0000556d"
    private val signed = SignedTransfer(hash, "04abcdef")

    // Going through the daemons

    private val took = mutableSetOf<String>()      // daemons that take a broadcast
    private val have = mutableSetOf<String>()      // daemons that have the transaction
    private val calls = mutableListOf<String>()

    private fun delivery(vararg daemons: String, timeoutMs: Long = 30_000, slow: Boolean = false) = TransferDelivery(
        broadcast = { daemon, blob ->
            calls.add("broadcast $daemon $blob")
            if (slow) delay(60_000)
            daemon in took
        },
        hasTransaction = { daemon, id ->
            calls.add("has $daemon $id")
            daemon in have
        },
        daemonAddresses = daemons.toList(),
        timeoutMs = timeoutMs,
    )

    @Test
    fun daemonThatTakesTheSameBytesSettlesIt() = runBlocking {
        took.add("b")

        assertEquals("b", delivery("b", "a").deliver(signed))
        assertEquals(listOf("broadcast b 04abcdef"), calls)
    }

    // A daemon refuses a transaction it already holds in a block
    @Test
    fun daemonThatRefusesTheBytesButHasTheTransactionSettlesIt() = runBlocking {
        have.add("b")

        assertEquals("b", delivery("b", "a").deliver(signed))
        assertEquals(listOf("broadcast b 04abcdef", "has b $hash"), calls)
    }

    @Test
    fun nextDaemonIsTriedWhenOneDoesNotHelp() = runBlocking {
        took.add("a")

        assertEquals("a", delivery("b", "a").deliver(signed))
        assertEquals(listOf("broadcast b 04abcdef", "has b $hash", "broadcast a 04abcdef"), calls)
    }

    @Test
    fun transferNoDaemonTakesOrHasStaysUnsettled() = runBlocking {
        assertNull(delivery("b", "a").deliver(signed))
    }

    // A transaction the daemon refused comes without its bytes: it can only be asked for
    @Test
    fun transferWithoutBytesIsOnlyAskedFor() = runBlocking {
        took.add("b")
        have.add("a")

        assertEquals("a", delivery("b", "a").deliver(SignedTransfer(hash, blobHex = null)))
        assertEquals(listOf("has b $hash", "has a $hash"), calls)
    }

    @Test
    fun daemonsThatTakeTooLongLeaveItUnsettled() = runBlocking {
        took.add("b")

        assertNull(delivery("b", timeoutMs = 100, slow = true).deliver(signed))
    }

    // The daemon calls themselves

    private val servers = mutableListOf<MockWebServer>()
    private val proxies = mutableListOf<ServerSocket>()

    @After
    fun tearDown() {
        servers.forEach { it.shutdown() }
        proxies.forEach { it.close() }
    }

    private fun daemon(vararg responses: MockResponse): MockWebServer =
        MockWebServer().apply {
            responses.forEach { enqueue(it) }
            start()
            servers.add(this)
        }

    private fun MockWebServer.address() = url("/").toString()

    private fun json(body: String) = MockResponse().setBody(body)

    private val direct = DaemonRpc(Proxy.NO_PROXY, timeoutMs = 2_000)

    @Test
    fun broadcastSendsTheBytesAndReadsTheStatus() = runBlocking {
        val daemon = daemon(json("""{"id":0,"jsonrpc":"2.0","result":{"status":"OK"}}"""))

        assertTrue(direct.broadcast(daemon.address(), "04abcdef"))

        val request = daemon.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/json_rpc", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("sendrawtransaction", body.getString("method"))
        assertEquals("04abcdef", body.getJSONObject("params").getString("tx_as_hex"))
    }

    @Test
    fun broadcastTheDaemonDoesNotTakeIsNotTaken() = runBlocking {
        listOf(
            json("""{"id":0,"jsonrpc":"2.0","result":{"status":"Failed"}}"""),
            json("""{"id":0,"jsonrpc":"2.0","result":{"status":"BUSY"}}"""),
            json("""{"id":0,"jsonrpc":"2.0","result":{"status":"DISCONNECTED"}}"""),
            json("""{"id":0,"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found"}}"""),
            json("<html>502 Bad Gateway</html>"),
            MockResponse().setResponseCode(500).setBody("""{"result":{"status":"OK"}}"""),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        ).forEach { response ->
            assertFalse(response.toString(), direct.broadcast(daemon(response).address(), "04abcdef"))
        }
    }

    @Test
    fun transactionTheDaemonHasIsFound() = runBlocking {
        val daemon = daemon(json("""{"id":0,"jsonrpc":"2.0","result":{"status":"OK","tx_info":{"id":"$hash","keeper_block":-1}}}"""))

        assertTrue(direct.hasTransaction(daemon.address(), hash))

        val body = JSONObject(daemon.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("get_tx_details", body.getString("method"))
        assertEquals(hash, body.getJSONObject("params").getString("tx_hash"))
    }

    // The first answer is what node.zano.org gives for a transaction it does not know
    @Test
    fun transactionTheDaemonDoesNotHaveIsNotFound() = runBlocking {
        listOf(
            json("""{"error":{"code":-14,"message":"tx is not found"},"id":0,"jsonrpc":"2.0","method":"get_tx_details"}"""),
            json("""{"id":0,"jsonrpc":"2.0","result":{"status":"OK"}}"""),
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
        ).forEach { response ->
            assertFalse(response.toString(), direct.hasTransaction(daemon(response).address(), hash))
        }
    }

    // Tor: the calls go where the engine's go

    /** Accepts one SOCKS5 client, notes where it asks to be connected, and refuses. */
    private fun socksProxy(asked: MutableList<String>): Proxy {
        val server = ServerSocket(0).also { proxies.add(it) }
        thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        input.readByte()                             // version
                        input.skipBytes(input.readUnsignedByte())    // the methods on offer
                        output.write(byteArrayOf(5, 0))              // no authentication
                        input.skipBytes(3)                           // version, connect, reserved
                        when (val type = input.readUnsignedByte()) {
                            3 -> {
                                val name = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                                asked.add("name ${String(name)}:${input.readUnsignedShort()}")
                            }
                            else -> asked.add("address of type $type")
                        }
                        output.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)) // general failure
                    }
                }
            } catch (e: Exception) {
                // closed by tearDown
            }
        }
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", server.localPort))
    }

    // The daemon's name reaches the proxy as a name: it is not looked up on the device
    @Test
    fun callsGoThroughTheSocksProxyWithTheHostUnresolved() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val rpc = DaemonRpc(socksProxy(asked), timeoutMs = 3_000)

        assertFalse(rpc.broadcast("https://node.zano.invalid", "04abcdef"))
        assertFalse(rpc.hasTransaction("http://node.zano.invalid:8081", hash))

        assertEquals(listOf("name node.zano.invalid:443", "name node.zano.invalid:8081"), asked.toList())
    }

    // With Tor on and its proxy gone, nothing is sent around it
    @Test
    fun nothingIsSentDirectlyWhenTheProxyDoesNotAnswer() = runBlocking {
        val daemon = daemon(json("""{"id":0,"jsonrpc":"2.0","result":{"status":"OK"}}"""))
        val closedPort = ServerSocket(0).use { it.localPort }
        val rpc = DaemonRpc(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", closedPort)), timeoutMs = 2_000)

        assertFalse(rpc.broadcast(daemon.address(), "04abcdef"))
        assertEquals(0, daemon.requestCount)
    }

    @Test
    fun proxyIsTakenFromTheJvmSettingsTheEngineIsGiven() {
        val host = System.getProperty("socksProxyHost")
        val port = System.getProperty("socksProxyPort")
        try {
            System.clearProperty("socksProxyHost")
            System.clearProperty("socksProxyPort")
            assertEquals(Proxy.NO_PROXY, jvmSocksProxy())

            System.setProperty("socksProxyHost", "127.0.0.1")
            System.setProperty("socksProxyPort", "9050")
            val proxy = jvmSocksProxy()
            val address = proxy.address() as InetSocketAddress
            assertEquals(Proxy.Type.SOCKS, proxy.type())
            assertEquals("127.0.0.1", address.hostString)
            assertEquals(9050, address.port)
            assertTrue(address.isUnresolved)

            System.setProperty("socksProxyPort", "tor")
            try {
                jvmSocksProxy()
                throw AssertionError("a port that is not a number was accepted")
            } catch (e: ZanoException) {
                // refused, as the engine's start refuses it
            }
        } finally {
            if (host == null) System.clearProperty("socksProxyHost") else System.setProperty("socksProxyHost", host)
            if (port == null) System.clearProperty("socksProxyPort") else System.setProperty("socksProxyPort", port)
        }
    }
}
