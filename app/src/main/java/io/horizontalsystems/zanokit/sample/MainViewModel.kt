package io.horizontalsystems.zanokit.sample

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.zanokit.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val kit: ZanoKit = ZanoKit.getInstance(
        context = app,
        wallet = ZanoWallet.Bip39(
            mnemonic = WalletConfig.SEED,
            passphrase = WalletConfig.PASSPHRASE,
            creationTimestamp = WalletConfig.CREATION_TIMESTAMP,
        ),
        walletId = WalletConfig.WALLET_ID,
        daemonAddress = WalletConfig.DAEMON_ADDRESS,
    )

    val syncStateFlow: StateFlow<SyncState> = kit.syncStateFlow
    val balancesFlow: StateFlow<List<BalanceInfo>> = kit.balancesFlow
    val assetsFlow: StateFlow<List<AssetInfo>> = kit.assetsFlow
    val transactionsFlow: StateFlow<List<TransactionInfo>> = kit.transactionsFlow

    private val _sendResult = MutableStateFlow<String?>(null)
    val sendResult: StateFlow<String?> = _sendResult

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    init {

        val address = ZanoKit.address(ZanoWallet.Bip39(WalletConfig.SEED, WalletConfig.PASSPHRASE, 0))
        Timber.e("++++++++++++ Address: $address")

        viewModelScope.launch {
            try {
                kit.start()
            } catch (e: Exception) {
                Timber.e(e, "ZanoKit start failed")
            }
        }
    }

    fun send(toAddress: String, amountStr: String, memo: String) {
        viewModelScope.launch {
            _sendResult.value = null
            _sendError.value = null
            try {
                val amount = amountStr.toLongOrNull()
                    ?: run { _sendError.value = "Invalid amount"; return@launch }
                val txHash = kit.send(
                    toAddress = toAddress,
                    amount = SendAmount.Value(amount),
                    memo = memo.takeIf { it.isNotBlank() },
                )
                _sendResult.value = txHash
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Send failed"
                Timber.e(e, "Send failed")
            }
        }
    }

    fun clearSendState() {
        _sendResult.value = null
        _sendError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { kit.stop() }
    }
}
