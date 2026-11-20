package io.horizontalsystems.zanokit

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentTransferLookupTest {

    private val asset = "a".repeat(64)

    private fun transfer(hash: String, vararg subtransfers: JSONObject) = JSONObject().apply {
        put("tx_hash", hash)
        put("subtransfers_by_pid", JSONArray().put(JSONObject().put("subtransfers", JSONArray(subtransfers.toList()))))
    }

    private fun sub(amount: Long, income: Boolean = false, assetId: String = ZANO_ASSET_ID) = JSONObject().apply {
        put("amount", amount)
        put("is_income", income)
        put("asset_id", assetId)
    }

    private val received = transfer("received", sub(5_000, income = true))

    private fun SentTransferLookup.findNow(
        knownBefore: Set<String>?,
        assetId: String,
        amount: Long,
        fee: Long,
        toSelf: Boolean = false,
    ) = runBlocking { find(knownBefore, assetId, amount, fee, toSelf) }

    // Each call of the wallet's list returns the next entry; the last one repeats
    private fun lookup(vararg lists: List<JSONObject>): SentTransferLookup {
        var call = 0
        var time = 0L
        return SentTransferLookup(
            recentTransfers = { lists[minOf(call++, lists.size - 1)] },
            timeoutMs = 3_000,
            intervalMs = 1_000,
            now = { time },
            sleep = { time += it },
        )
    }

    @Test
    fun transferThatAppearsAfterTheSendIsFound() {
        val sent = transfer("sent", sub(1_010))
        val lookup = lookup(listOf(received), listOf(received), listOf(sent, received))

        assertEquals("sent", lookup.findNow(lookup.knownHashes(), ZANO_ASSET_ID, amount = 1_000, fee = 10))
    }

    @Test
    fun transferListedBeforeTheSendIsNotTakenForIt() {
        val earlier = transfer("earlier", sub(1_010))
        val lookup = lookup(listOf(earlier, received))

        assertNull(lookup.findNow(lookup.knownHashes(), ZANO_ASSET_ID, amount = 1_000, fee = 10))
    }

    @Test
    fun transferOfAnotherAmountIsNotTakenForIt() {
        val other = transfer("other", sub(2_010))
        val lookup = lookup(listOf(received), listOf(other, received))

        assertNull(lookup.findNow(lookup.knownHashes(), ZANO_ASSET_ID, amount = 1_000, fee = 10))
    }

    @Test
    fun incomingTransferOfTheSameAmountIsNotTakenForIt() {
        val incoming = transfer("incoming", sub(1_000, income = true))
        val lookup = lookup(listOf(received), listOf(incoming, received))

        assertNull(lookup.findNow(lookup.knownHashes(), ZANO_ASSET_ID, amount = 1_000, fee = 10))
    }

    // An asset transfer lists the asset amount and, apart from it, the fee paid in ZANO
    @Test
    fun assetTransferIsFoundByItsAssetAmount() {
        val sent = transfer("sent", sub(10), sub(700, assetId = asset))
        val lookup = lookup(listOf(received), listOf(sent, received))

        assertEquals("sent", lookup.findNow(lookup.knownHashes(), asset, amount = 700, fee = 10))
    }

    @Test
    fun feeOfAnAssetTransferIsNotTakenForANativeTransfer() {
        val assetTransfer = transfer("asset", sub(10), sub(700, assetId = asset))
        val lookup = lookup(listOf(received), listOf(assetTransfer, received))

        assertNull(lookup.findNow(lookup.knownHashes(), ZANO_ASSET_ID, amount = 700, fee = 10))
    }

    // What was sent came back, so the wallet lists the fee alone
    @Test
    fun transferToTheWalletsOwnAddressIsFoundByItsFee() {
        val sent = transfer("sent", sub(10))
        val lookup = lookup(listOf(received), listOf(sent, received))

        assertEquals("sent", lookup.findNow(lookup.knownHashes(), ZANO_ASSET_ID, amount = 1_000, fee = 10, toSelf = true))
    }

    @Test
    fun assetTransferToTheWalletsOwnAddressIsFoundByItsFee() {
        val sent = transfer("sent", sub(10))
        val lookup = lookup(listOf(received), listOf(sent, received))

        assertEquals("sent", lookup.findNow(lookup.knownHashes(), asset, amount = 700, fee = 10, toSelf = true))
    }

    @Test
    fun feeSizedTransferIsNotTakenForASendToSomeoneElse() {
        val other = transfer("other", sub(10))
        val lookup = lookup(listOf(received), listOf(other, received))

        assertNull(lookup.findNow(lookup.knownHashes(), ZANO_ASSET_ID, amount = 1_000, fee = 10))
    }

    // Without the list from before the send, an old transfer cannot be told from the new one
    @Test
    fun nothingIsFoundWhenTheListCouldNotBeReadBeforeTheSend() {
        val sent = transfer("sent", sub(1_010))
        val lookup = lookup(emptyList(), listOf(sent, received))

        assertNull(lookup.knownHashes())
        assertNull(lookup.findNow(null, ZANO_ASSET_ID, amount = 1_000, fee = 10))
    }

    @Test
    fun listThatCannotBeReadDoesNotEndTheLookup() {
        val sent = transfer("sent", sub(1_010))
        var call = 0
        var time = 0L
        val lookup = SentTransferLookup(
            recentTransfers = { if (call++ == 0) error("wallet is busy") else listOf(sent, received) },
            timeoutMs = 3_000,
            intervalMs = 1_000,
            now = { time },
            sleep = { time += it },
        )

        assertEquals("sent", lookup.findNow(setOf("received"), ZANO_ASSET_ID, amount = 1_000, fee = 10))
    }

    @Test
    fun lookupGivesUpAfterItsTimeout() {
        var calls = 0
        var time = 0L
        val lookup = SentTransferLookup(
            recentTransfers = { calls++; listOf(received) },
            timeoutMs = 3_000,
            intervalMs = 1_000,
            now = { time },
            sleep = { time += it },
        )

        assertNull(lookup.findNow(setOf("received"), ZANO_ASSET_ID, amount = 1_000, fee = 10))
        assertEquals(4, calls)
    }
}
