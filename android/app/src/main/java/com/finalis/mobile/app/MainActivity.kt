package com.finalis.mobile.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.wallet.SubmittedTransactionManager
import com.finalis.mobile.core.wallet.WalletSafetyManager
import com.finalis.mobile.core.wallet.WalletSendService
import com.finalis.mobile.core.wallet.WalletSessionManager
import com.finalis.mobile.data.wallet.AndroidSecureWalletStore
import com.finalis.mobile.data.wallet.AndroidSubmittedTransactionStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinalisWalletTheme {
                val rpcSettingsRepository = remember { RpcSettingsRepository(applicationContext) }
                val repository = remember { createLightserverRepository(rpcSettingsRepository) }
                val walletSessionManager = remember {
                    WalletSessionManager(
                        walletStore = AndroidSecureWalletStore(applicationContext),
                    )
                }
                val submittedTransactionManager = remember {
                    SubmittedTransactionManager(
                        submittedTransactionStore = AndroidSubmittedTransactionStore(applicationContext),
                    )
                }
                val walletSafetyManager = remember {
                    WalletSafetyManager(
                        walletSessionManager = walletSessionManager,
                        submittedTransactionManager = submittedTransactionManager,
                    )
                }
                val sendService = remember { WalletSendService() }
                val walletViewModel = remember {
                    WalletViewModel(
                        repository = repository,
                        rpcSettingsRepository = rpcSettingsRepository,
                        walletSessionManager = walletSessionManager,
                        submittedTransactionManager = submittedTransactionManager,
                        walletSafetyManager = walletSafetyManager,
                    )
                }
                val sendViewModel = remember {
                    SendViewModel(
                        sendService = sendService,
                    )
                }
                val historyViewModel = remember { HistoryViewModel() }
                val scope = rememberCoroutineScope()
                val lifecycleOwner = LocalLifecycleOwner.current
                var selectedTabName by rememberSaveable { mutableStateOf(WalletTab.Home.name) }
                val selectedTab = WalletTab.valueOf(selectedTabName)

                fun copyText(label: String, value: String) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                    Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
                }

                fun shareText(subject: String, value: String) {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, subject)
                                putExtra(Intent.EXTRA_TEXT, value)
                            },
                            subject,
                        ),
                    )
                }

                LaunchedEffect(Unit) {
                    walletViewModel.loadStartup()
                    walletViewModel.walletRecord?.let { wallet ->
                        walletViewModel.startSubmittedPolling(scope, wallet)
                    }
                }

                LaunchedEffect(walletViewModel.readState) {
                    historyViewModel.bind(walletViewModel.readState)
                }

                DisposableEffect(lifecycleOwner, walletViewModel.walletRecord?.privateKeyHex) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            walletViewModel.walletRecord?.let { wallet ->
                                scope.launch {
                                    walletViewModel.refreshDashboardOnResume(wallet)
                                    walletViewModel.startSubmittedPolling(scope, wallet)
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                Scaffold(
                    topBar = { WalletTopBar(network = walletViewModel.activeNetwork) },
                    bottomBar = {
                        if (walletViewModel.startupChecked && walletViewModel.walletRecord != null) {
                            WalletBottomBar(
                                selectedTab = selectedTab,
                                onSelectTab = { selectedTabName = it.name },
                            )
                        }
                    },
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (!walletViewModel.startupChecked || walletViewModel.walletRecord == null) {
                            WalletStartupContent(
                                modifier = Modifier.padding(innerPadding),
                                startupChecked = walletViewModel.startupChecked,
                                diagnosticsState = walletViewModel.diagnosticsState,
                                importPrivateKeyHex = walletViewModel.importPrivateKeyHex,
                                importError = walletViewModel.importError,
                                isImporting = walletViewModel.isImporting,
                                onImportPrivateKeyChange = walletViewModel::onImportPrivateKeyChange,
                                onImportWallet = {
                                    scope.launch {
                                        val importedWallet = walletViewModel.importWallet()
                                        if (importedWallet != null) {
                                            selectedTabName = WalletTab.Home.name
                                            sendViewModel.clearAllState()
                                            historyViewModel.bind(walletViewModel.readState)
                                        }
                                    }
                                },
                                rpcSettingsState = walletViewModel.rpcSettingsState,
                                onRpcEndpointInputChange = walletViewModel::onRpcEndpointInputChange,
                                onAddEndpoint = {
                                    scope.launch {
                                        walletViewModel.addRpcEndpoint()
                                    }
                                },
                                onSelectEndpoint = { endpoint ->
                                    scope.launch {
                                        walletViewModel.selectRpcEndpoint(endpoint)
                                    }
                                },
                                onRemoveEndpoint = walletViewModel::removeRpcEndpoint,
                                selectedNetwork = walletViewModel.activeNetwork,
                                onSelectNetwork = { network ->
                                    scope.launch {
                                        walletViewModel.selectOnboardingNetwork(network)
                                    }
                                },
                            )
                        } else {
                            val currentWallet = walletViewModel.walletRecord!!
                            WalletTabContent(
                                modifier = Modifier.padding(innerPadding),
                                selectedTab = selectedTab,
                                wallet = currentWallet,
                                readState = historyViewModel.state,
                                diagnosticsState = walletViewModel.diagnosticsState,
                                rpcSettingsState = walletViewModel.rpcSettingsState,
                                exportedPrivateKeyHex = walletViewModel.exportedPrivateKeyHex,
                                exportArmed = walletViewModel.exportArmed,
                                resetArmed = walletViewModel.resetArmed,
                                isRefreshing = walletViewModel.isRefreshing,
                                recipientAddress = sendViewModel.recipientAddress,
                                amountUnitsInput = sendViewModel.amountUnitsInput,
                                errorMessage = sendViewModel.errorMessage,
                                previewState = sendViewModel.previewState,
                                broadcastResult = sendViewModel.broadcastResult,
                                isSending = sendViewModel.isSending,
                                onSelectTab = { selectedTabName = it.name },
                                onRefresh = {
                                    scope.launch {
                                        walletViewModel.refreshDashboard(currentWallet)
                                    }
                                },
                                onLoadMoreHistory = {
                                    scope.launch {
                                        walletViewModel.loadMoreHistory()
                                        historyViewModel.bind(walletViewModel.readState)
                                    }
                                },
                                onResumePolling = {
                                    walletViewModel.startSubmittedPolling(scope, currentWallet)
                                },
                                onToggleExport = walletViewModel::onToggleExport,
                                onHideKey = walletViewModel::hideExportedKey,
                                onToggleReset = {
                                    scope.launch {
                                        val resetConfirmed = walletViewModel.onToggleReset()
                                        if (resetConfirmed) {
                                            selectedTabName = WalletTab.Home.name
                                            sendViewModel.clearAllState()
                                            historyViewModel.clear()
                                        }
                                    }
                                },
                                onCopyPrivateKey = { key -> copyText("Private key", key) },
                                onCopyAddress = { copyText("Wallet address", currentWallet.walletProfile.address.value) },
                                onShareAddress = { shareText("Finalis address", currentWallet.walletProfile.address.value) },
                                onRecipientAddressChange = sendViewModel::onRecipientAddressChange,
                                onAmountUnitsChange = sendViewModel::onAmountUnitsChange,
                                onNormalizeAmountUnitsInput = sendViewModel::normalizeAmountUnitsInput,
                                onPrepareTransaction = {
                                    scope.launch {
                                        sendViewModel.prepareTransaction(
                                            wallet = currentWallet,
                                            repository = repository,
                                            submittedTransactionManager = submittedTransactionManager,
                                            currentSpendableUnits = (historyViewModel.state as? DashboardState.Ready)?.spendableUnits,
                                            currentReservedUnits = (historyViewModel.state as? DashboardState.Ready)?.reservedUnits,
                                        )
                                        walletViewModel.syncRpcSettingsState()
                                    }
                                },
                                onBroadcastTransaction = {
                                    scope.launch {
                                        walletViewModel.refreshDashboard(currentWallet)
                                        historyViewModel.bind(walletViewModel.readState)
                                        sendViewModel.broadcastPreparedTransaction(
                                            walletAddress = currentWallet.walletProfile.address.value,
                                            repository = repository,
                                            submittedTransactionManager = submittedTransactionManager,
                                            onAccepted = {
                                            walletViewModel.refreshDashboard(currentWallet)
                                            walletViewModel.startSubmittedPolling(scope, currentWallet)
                                            historyViewModel.bind(walletViewModel.readState)
                                            },
                                            onNeedsRefresh = {
                                            walletViewModel.refreshDashboard(currentWallet)
                                            historyViewModel.bind(walletViewModel.readState)
                                            },
                                        )
                                        walletViewModel.syncRpcSettingsState()
                                    }
                                },
                                onCopyPrimaryAddress = { copyText("Wallet address", currentWallet.walletProfile.address.value) },
                                onCopyTxid = { copyText("Transaction id", it) },
                                onRpcEndpointInputChange = walletViewModel::onRpcEndpointInputChange,
                                onAddEndpoint = {
                                    scope.launch {
                                        val updated = walletViewModel.addRpcEndpoint()
                                        if (updated) {
                                            walletViewModel.refreshDashboard(currentWallet)
                                            historyViewModel.bind(walletViewModel.readState)
                                        }
                                    }
                                },
                                onSelectEndpoint = { endpoint ->
                                    scope.launch {
                                        val updated = walletViewModel.selectRpcEndpoint(endpoint)
                                        if (updated) {
                                            walletViewModel.refreshDashboard(currentWallet)
                                            historyViewModel.bind(walletViewModel.readState)
                                        }
                                    }
                                },
                                onRemoveEndpoint = { endpoint ->
                                    walletViewModel.removeRpcEndpoint(endpoint)
                                    scope.launch {
                                        walletViewModel.refreshDashboard(currentWallet)
                                        historyViewModel.bind(walletViewModel.readState)
                                    }
                                },
                                onSwitchNetwork = { network ->
                                    scope.launch {
                                        sendViewModel.clearAllState()
                                        historyViewModel.clear()
                                        val rebound = walletViewModel.switchNetwork(network)
                                        historyViewModel.bind(walletViewModel.readState)
                                        if (rebound != null) {
                                            walletViewModel.startSubmittedPolling(scope, rebound)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WalletStartupContent(
    modifier: Modifier,
    startupChecked: Boolean,
    diagnosticsState: DiagnosticsState,
    importPrivateKeyHex: String,
    importError: String?,
    isImporting: Boolean,
    onImportPrivateKeyChange: (String) -> Unit,
    onImportWallet: () -> Unit,
    rpcSettingsState: RpcSettingsState,
    onRpcEndpointInputChange: (String) -> Unit,
    onAddEndpoint: () -> Unit,
    onSelectEndpoint: (RpcEndpoint) -> Unit,
    onRemoveEndpoint: (RpcEndpoint) -> Unit,
    selectedNetwork: FinalisNetwork,
    onSelectNetwork: (FinalisNetwork) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            BrandHeroCard(
                mode = if (BuildConfig.USE_MOCK_LIGHTSERVER) "Mock Lightserver" else "Runtime Lightserver",
                networkLabel = "${selectedNetwork.name} / ${selectedNetwork.hrp}",
                defaultFeeUnits = WalletSendService.DEFAULT_FEE_UNITS,
            )
        }
        item {
            EndpointHealthBanner(
                diagnosticsState = diagnosticsState,
            )
        }
        if (!startupChecked) {
            item {
                StartupSection()
            }
        } else {
            item {
                NetworkSelectionSection(
                    selectedNetwork = selectedNetwork,
                    title = "Network",
                    subtitle = "The imported wallet is bound to this network and uses its address prefix.",
                    enabled = !isImporting,
                    onSelectNetwork = onSelectNetwork,
                )
            }
            item {
                WalletImportSection(
                    importPrivateKeyHex = importPrivateKeyHex,
                    importError = importError,
                    isImporting = isImporting,
                    onImportPrivateKeyChange = onImportPrivateKeyChange,
                    onImportWallet = onImportWallet,
                )
            }
            item {
                DiagnosticsSection(
                    rpcSettingsState = rpcSettingsState,
                    diagnosticsState = diagnosticsState,
                    onRpcEndpointInputChange = onRpcEndpointInputChange,
                    onAddEndpoint = onAddEndpoint,
                    onSelectEndpoint = onSelectEndpoint,
                    onRemoveEndpoint = onRemoveEndpoint,
                )
            }
        }
    }
}
