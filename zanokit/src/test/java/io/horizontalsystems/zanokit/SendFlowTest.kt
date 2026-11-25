package io.horizontalsystems.zanokit

import io.horizontalsystems.zanokit.SendResult.Reason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SendFlowTest {

    private val hash = "e44e5e29" + "0".repeat(48) + "0000556d"
    private val signed = SignedTransfer(hash, "04abcdef")

    private val unreachable = NodeUnreachableException("no connection to daemon, request = getrandom_outs4.bin")
    private fun unanswered(transfer: SignedTransfer? = signed) = BroadcastUnconfirmedException("no answer to the broadcast", transfer)

    // What each try of the transfer does, in order
    private val tries = ArrayDeque<() -> String>()
    private var built = 0
    private var otherDaemon: String? = "node-b"
    private var deliveredBy: String? = null
    private val daemons = mutableListOf<String>()
    private val delivered = mutableListOf<SignedTransfer>()

    private fun send(): SendResult = runBlocking {
        SendFlow(
            transfer = {
                built++
                tries.removeFirst()()
            },
            otherDaemonThatAnswers = { otherDaemon },
            setDaemon = { daemons.add(it) },
            deliver = {
                delivered.add(it)
                deliveredBy
            },
        ).run()
    }

    @Test
    fun transferTheDaemonTakesIsSent() {
        tries.add { hash }

        assertEquals(SendResult.Sent(hash), send())
        assertEquals(emptyList<String>(), daemons)
    }

    // Nothing was sent, so building the transfer again on another daemon cannot pay twice
    @Test
    fun daemonFailingBeforeTheBroadcastIsLeftAndTheTransferBuiltAgain() {
        tries.add { throw unreachable }
        tries.add { hash }

        assertEquals(SendResult.Sent(hash), send())
        assertEquals(listOf("node-b"), daemons)
        assertEquals(2, built)
    }

    @Test
    fun secondDaemonFailingBeforeTheBroadcastEndsIt() {
        tries.add { throw unreachable }
        tries.add { throw unreachable }

        assertEquals(SendResult.NotSent(Reason.NodeUnreachable, unreachable.message), send())
        assertEquals(2, built)
    }

    @Test
    fun noOtherDaemonLeavesTheTransferNotSent() {
        otherDaemon = null
        tries.add { throw unreachable }

        assertEquals(SendResult.NotSent(Reason.NodeUnreachable, unreachable.message), send())
        assertEquals(1, built)
        assertEquals(emptyList<String>(), daemons)
    }

    // The same signed bytes go to the daemons; the wallet follows them
    @Test
    fun unansweredBroadcastADaemonSettlesIsSent() {
        deliveredBy = "node-c"
        tries.add { throw unanswered() }

        assertEquals(SendResult.Sent(hash), send())
        assertEquals(listOf(signed), delivered)
        assertEquals(listOf("node-c"), daemons)
        assertEquals(1, built)
    }

    // The one thing that must never happen: a second transfer after one that may be out
    @Test
    fun unansweredBroadcastNoDaemonSettlesIsUnknownAndNeverBuiltAgain() {
        tries.add { throw unanswered() }

        assertEquals(SendResult.Unknown(hash, "no answer to the broadcast"), send())
        assertEquals(1, built)
        assertEquals(emptyList<String>(), daemons)
    }

    @Test
    fun unansweredBroadcastWithoutATransactionIsUnknown() {
        tries.add { throw unanswered(transfer = null) }

        assertEquals(SendResult.Unknown(null, "no answer to the broadcast"), send())
        assertEquals(emptyList<SignedTransfer>(), delivered)
        assertEquals(1, built)
    }

    // On the second daemon the broadcast goes unanswered: settled, not built a third time
    @Test
    fun unansweredBroadcastAfterAMoveIsSettledToo() {
        deliveredBy = "node-c"
        tries.add { throw unreachable }
        tries.add { throw unanswered() }

        assertEquals(SendResult.Sent(hash), send())
        assertEquals(listOf("node-b", "node-c"), daemons)
        assertEquals(2, built)
    }

    // A refused transaction may be a repeat of one the daemon took
    @Test
    fun rejectedTransactionADaemonHasIsSent() {
        deliveredBy = "node-a"
        tries.add { throw SendFailedException("rejected by daemon", SignedTransfer(hash, null)) }

        assertEquals(SendResult.Sent(hash), send())
    }

    @Test
    fun rejectedTransactionNoDaemonHasIsNotSent() {
        tries.add { throw SendFailedException("rejected by daemon", SignedTransfer(hash, null)) }

        assertEquals(SendResult.NotSent(Reason.Failed, "rejected by daemon"), send())
        assertEquals(1, built)
    }

    @Test
    fun missingFundsAreNotSent() {
        tries.add { throw InsufficientFundsException("not enough money") }

        assertEquals(SendResult.NotSent(Reason.InsufficientFunds, "not enough money"), send())
        assertEquals(emptyList<String>(), daemons)
    }
}
