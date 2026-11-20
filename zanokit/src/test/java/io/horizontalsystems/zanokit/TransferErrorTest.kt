package io.horizontalsystems.zanokit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferErrorTest {

    // The texts are what the wallet engine returns with patches/0005: an error code, the
    // exception with its source location, then the message with the daemon call that failed.
    private fun noConnection(request: String) =
        "WALLET_RPC_ERROR_CODE_GENERIC_ERROR: [N5tools5error23no_connection_to_daemonE] " +
            "Zano/src/wallet/wallet2.cpp:7310\n  no connection to daemon, request = $request"

    // An engine without patches/0005 names no call, for a failed decoy request and for an
    // unanswered broadcast alike. Seen on a device with the 2.2.3.601 libraries.
    private val noConnectionWithoutACall =
        "WALLET_RPC_ERROR_CODE_GENERIC_ERROR: [N5tools5error23no_connection_to_daemonE] " +
            "Zano/src/wallet/wallet2.cpp:7310\n  no connection to daemon"

    @Test
    fun errorThatNamesNoCallIsUnconfirmed() {
        assertTrue(parseTransferError(noConnectionWithoutACall) is BroadcastUnconfirmedException)
    }

    @Test
    fun callThatFailsBeforeTheBroadcastIsAnUnreachableNode() {
        assertTrue(parseTransferError(noConnection("getrandom_outs4.bin")) is NodeUnreachableException)
    }

    // The daemon may have taken the transaction and only its answer was lost
    @Test
    fun broadcastWithoutAnAnswerIsUnconfirmed() {
        assertTrue(parseTransferError(noConnection("sendrawtransaction")) is BroadcastUnconfirmedException)
    }

    // A call this list does not know must not read as safe to repeat
    @Test
    fun unknownCallWithoutAnAnswerIsUnconfirmed() {
        assertTrue(parseTransferError(noConnection("send_raw_tx_v2")) is BroadcastUnconfirmedException)
        assertTrue(parseTransferError(noConnection("Failed to build TOR stream")) is BroadcastUnconfirmedException)
        assertTrue(parseTransferError(noConnection("getrandom_outs4.bin and sendrawtransaction")) is BroadcastUnconfirmedException)
        assertTrue(parseTransferError("no connection to daemon") is BroadcastUnconfirmedException)
    }

    @Test
    fun callsThatOnlyReadAreAnUnreachableNode() {
        listOf("getrandom_outs3.bin", "get_o_indexes.bin", "getblocks.bin", "get_blocks_details", "get_tx_pool").forEach {
            assertTrue(it, parseTransferError(noConnection(it)) is NodeUnreachableException)
        }
    }

    // The daemon answered that it has no peers, so it did not take the transaction
    @Test
    fun daemonThatAnswersItIsOfflineIsAnUnreachableNode() {
        assertTrue(parseTransferError(noConnection("Transfer attempt while daemon offline")) is NodeUnreachableException)
    }

    // The daemon answered that it is busy, so it did not take the transaction
    @Test
    fun busyDaemonAtTheBroadcastIsAnUnreachableNode() {
        val message = "WALLET_RPC_ERROR_CODE_DAEMON_IS_BUSY: [N5tools5error11daemon_busyE] " +
            "Zano/src/wallet/wallet2.cpp:7618\n  daemon is busy, request = sendrawtransaction"
        assertTrue(parseTransferError(message) is NodeUnreachableException)
    }

    @Test
    fun missingFundsAreNotANodeFailure() {
        assertTrue(parseTransferError("WALLET_RPC_ERROR_CODE_NOT_ENOUGH_MONEY: not enough money") is InsufficientFundsException)
    }

    @Test
    fun rejectedTransactionIsAFailedSend() {
        assertTrue(parseTransferError("transaction was rejected by daemon with status: Failed") is SendFailedException)
    }

    @Test
    fun onlyAnUnansweredOrRejectedBroadcastMayHaveBeenSent() {
        val rejected = "WALLET_RPC_ERROR_CODE_GENERIC_TRANSFER_ERROR: [N5tools5error11tx_rejectedE] " +
            "Zano/src/wallet/wallet2.cpp:7620\n  transaction was rejected by daemon, status = Failed, tx: {}"

        assertTrue(parseTransferError(noConnection("sendrawtransaction")).mayHaveBeenSent)
        assertTrue(parseTransferError(rejected).mayHaveBeenSent)
        assertFalse(parseTransferError(noConnection("getrandom_outs4.bin")).mayHaveBeenSent)
        assertFalse(parseTransferError("WALLET_RPC_ERROR_CODE_NOT_ENOUGH_MONEY: not enough money").mayHaveBeenSent)
        assertFalse(parseTransferError("BUSY").mayHaveBeenSent)
    }

    @Test
    fun errorWithoutATextIsAFailedSend() {
        assertTrue(parseTransferError(null) is SendFailedException)
    }
}
