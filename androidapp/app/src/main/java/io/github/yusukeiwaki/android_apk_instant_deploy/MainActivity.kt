package io.github.yusukeiwaki.android_apk_instant_deploy

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

class MainActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var store: AppStore
    private lateinit var apiClient: ApiClient
    private lateinit var downloadStore: ApkDownloadStore
    private var screenState by mutableStateOf<ScreenState>(ScreenState.Loading("確認中", "しばらくお待ちください。", false))
    private var downloadRefreshTick by mutableIntStateOf(0)
    private var isHomeRefreshing by mutableStateOf(false)
    private var displayedPolicySnapshot: ApiClient.PolicySnapshot? = null
    private var displayedNotifications: List<ApiClient.DeviceNotification> = emptyList()
    private var showingPolicyHome = false
    private var displayedHomeActiveDownloadCount = 0
    private var downloadRefreshScheduled = false
    private var activeServerBaseUrl = ""
    private var activeDeviceAuthToken = ""
    private var fcmPolicyReceiverRegistered = false
    private var restrictionsReceiverRegistered = false
    private var managedRegistrationInProgress = false
    private var lastManagedRegistrationAttemptKey = ""
    private var lastHandledRegistrationDeepLink = ""
    private var activeRegistrationRequestKey = ""
    private var registrationDeepLinkInForeground = false
    private var pendingRequiredInstallOpen: RequiredInstallOpen? = null
    private val fcmPolicyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (FcmMessagingService.ACTION_POLICY_UPDATED == intent.action && store.isRegistered) {
                store.setPendingPolicyFetch(true)
                fetchPolicy()
            }
        }
    }
    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED != intent.action) {
                return
            }
            if (store.isRegistered) {
                if (handleManagedServerBaseUrlUpdateIfRegistered()) {
                    syncApiClientFromStore()
                    displayedPolicySnapshot = null
                    displayedNotifications = emptyList()
                    showHome(true)
                }
            } else {
                handleManagedRegistrationIfAvailable()
            }
        }
    }
    private val downloadStatusRefreshRunnable = object : Runnable {
        override fun run() {
            downloadRefreshScheduled = false
            refreshDownloadStatusViews()
            if (hasVisibleDownloadUi() && activeDownloadCount() > 0) {
                startDownloadStatusRefresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)
        syncApiClientFromStore()
        downloadStore = ApkDownloadStore(this)
        registerFcmPolicyReceiver()
        registerRestrictionsReceiver()
        setContent {
            AppTheme {
                AppScaffold(screenState, downloadRefreshTick)
            }
        }
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (handleUnconsumedRegistrationDeepLinkOnResume()) {
            return
        }
        if (registrationDeepLinkInForeground && !store.isRegistered) {
            return
        }
        if (!store.isRegistered && handleManagedRegistrationIfAvailable()) {
            return
        }
        if (store.isRegistered) {
            handleManagedServerBaseUrlUpdateIfRegistered()
        }
        if (syncApiClientFromStore() && store.isRegistered) {
            displayedPolicySnapshot = null
            displayedNotifications = emptyList()
            showHome(true)
            return
        }
        refreshDownloadStatusViews()
        displayedPolicySnapshot?.let { syncRequiredInstallNotifications(it) }
        startDownloadStatusRefresh()
    }

    override fun onPause() {
        stopDownloadStatusRefresh()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            displayedPolicySnapshot?.let { syncRequiredInstallNotifications(it) }
        }
    }

    override fun onDestroy() {
        stopDownloadStatusRefresh()
        unregisterFcmPolicyReceiver()
        unregisterRestrictionsReceiver()
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBack()
    }

    private fun handleIntent(intent: Intent) {
        if (RequiredAppNotificationReceiver.ACTION_OPEN_REQUIRED_APP == intent.action) {
            handleRequiredInstallOpen(intent)
            return
        }

        val data = intent.data
        if (data != null && isRegistrationDeepLink(data)) {
            registrationDeepLinkInForeground = true
            lastHandledRegistrationDeepLink = data.toString()
            val serverBaseUrl = data.getQueryParameter("server_base_url")
            registrationLog("manual_link_received identifier=${data.getQueryParameter("identifier").debugTail()} server=${serverBaseUrl.orEmpty()} registered=${store.isRegistered}")
            if (store.isRegistered) {
                showAlreadyRegistered(serverBaseUrl)
                return
            }
            val identifier = data.getQueryParameter("identifier")
            val secret = data.getQueryParameter("secret")
            when {
                identifier.isNullOrBlank() || secret.isNullOrBlank() -> {
                    showRegisterBlocked("このリンクは使えません", "新しい登録リンクを管理者に依頼してください。")
                }
                !serverBaseUrl.isNullOrBlank() && !isHttpUrl(serverBaseUrl) -> {
                    showRegisterBlocked("このリンクは使えません", "サーバーURLが正しくありません。新しい登録リンクを管理者に依頼してください。")
                }
                else -> {
                    if (!serverBaseUrl.isNullOrBlank()) {
                        store.setServerBaseUrl(serverBaseUrl)
                        syncApiClientFromStore()
                    }
                    showRegister(identifier, secret)
                }
            }
            return
        }

        registrationDeepLinkInForeground = false
        if (store.isRegistered) {
            showHome(false)
        } else if (handleManagedRegistrationIfAvailable()) {
            // Screen transition is handled by the managed configuration path.
        } else {
            showLinkRequired()
        }
    }

    private fun setScreen(next: ScreenState) {
        showingPolicyHome = next is ScreenState.PolicyHome
        screenState = next
    }

    private fun showLinkRequired() {
        setScreen(ScreenState.LinkRequired)
    }

    private fun showRegister(identifier: String, secret: String) {
        activeRegistrationRequestKey = ""
        setScreen(ScreenState.Register(identifier, secret, "", "通知トークンを準備しています...", ""))
        executor.execute {
            try {
                val fcmToken = FcmTokenProvider().obtainToken(this)
                store.saveFcmToken(fcmToken)
                mainHandler.post {
                    val current = screenState as? ScreenState.Register
                    if (current != null && current.identifier == identifier && current.secret == secret) {
                        setScreen(current.copy(tokenStatus = "通知トークンを取得しました", fcmToken = fcmToken))
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    val current = screenState as? ScreenState.Register
                    if (current != null && current.identifier == identifier && current.secret == secret) {
                        setScreen(current.copy(tokenStatus = "通知トークンを取得できませんでした"))
                        registrationLog("manual_registration_fcm_token_failed identifier=${identifier.debugTail()}")
                    }
                }
            }
        }
    }

    private fun updateRegisterDisplayName(value: String) {
        val current = screenState as? ScreenState.Register ?: return
        setScreen(current.copy(displayName = value))
    }

    private fun registerDevice(identifier: String, secret: String, displayName: String, fcmToken: String) {
        val requestKey = registrationRequestKey(identifier, secret, displayName, fcmToken)
        activeRegistrationRequestKey = requestKey
        registrationLog("manual_registration_started identifier=${identifier.debugTail()} server=${store.serverBaseUrl()} displayNameLength=${displayName.length}")
        showLoading("登録しています")
        executor.execute {
            try {
                val device = ApiClient(store.serverBaseUrl(), "").registerDevice(identifier, secret, displayName, fcmToken)
                mainHandler.post {
                    if (activeRegistrationRequestKey != requestKey) {
                        return@post
                    }
                    activeRegistrationRequestKey = ""
                    store.saveRegistration(device, fcmToken)
                    syncApiClientFromStore()
                    registrationLog("manual_registration_succeeded identifier=${identifier.debugTail()}")
                    showRegistrationDone()
                }
            } catch (e: ApiClient.ApiException) {
                mainHandler.post {
                    if (activeRegistrationRequestKey != requestKey) {
                        return@post
                    }
                    activeRegistrationRequestKey = ""
                    registrationLog("manual_registration_api_error identifier=${identifier.debugTail()} httpStatus=${e.httpStatus} code=${e.code}")
                    when (e.code) {
                        "DEVICE_ALREADY_REGISTERED", "REGISTRATION_TOKEN_NOT_FOUND" -> {
                            showRegisterBlocked("このリンクは使えません", "新しい登録リンクを管理者に依頼してください。")
                        }
                        else -> showRegisterBlocked("登録できませんでした", registrationApiFailureMessage(e))
                    }
                }
            } catch (e: IOException) {
                mainHandler.post {
                    if (activeRegistrationRequestKey != requestKey) {
                        return@post
                    }
                    activeRegistrationRequestKey = ""
                    registrationLog("manual_registration_io_error identifier=${identifier.debugTail()} error=${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                    showRegisterRetryableError(identifier, secret, displayName, fcmToken, registrationIoFailureMessage(e))
                }
            } catch (_: JSONException) {
                mainHandler.post {
                    if (activeRegistrationRequestKey != requestKey) {
                        return@post
                    }
                    activeRegistrationRequestKey = ""
                    registrationLog("manual_registration_response_parse_error identifier=${identifier.debugTail()}")
                    showRegisterRetryableError(identifier, secret, displayName, fcmToken, registrationResponseParseFailureMessage())
                }
            }
        }
    }

    private fun handleManagedRegistrationIfAvailable(): Boolean {
        if (store.isRegistered || managedRegistrationInProgress) {
            return false
        }

        val config = managedRegistrationConfig() ?: return false
        if (config.identifier.isEmpty() || config.secret.isEmpty()) {
            registrationLog("managed_registration_config_incomplete identifier=${config.identifier.debugTail()} hasSecret=${config.secret.isNotEmpty()}")
            return false
        }

        val attemptKey = config.attemptKey()
        if (lastManagedRegistrationAttemptKey == attemptKey && screenState is ScreenState.RegisterBlocked) {
            return true
        }

        if (config.serverBaseUrl.isNotEmpty()) {
            if (!isHttpUrl(config.serverBaseUrl)) {
                lastManagedRegistrationAttemptKey = attemptKey
                showRegisterBlocked("管理設定を確認してください", "サーバーURLが正しくありません。")
                return true
            }
            store.setServerBaseUrl(config.serverBaseUrl)
            syncApiClientFromStore()
        }

        if (config.displayName.isEmpty()) {
            registrationLog("managed_registration_needs_display_name identifier=${config.identifier.debugTail()}")
            showRegister(config.identifier, config.secret)
        } else {
            registerManagedDevice(config, attemptKey)
        }
        return true
    }

    private fun handleManagedServerBaseUrlUpdateIfRegistered(): Boolean {
        if (!store.isRegistered) {
            return false
        }
        val config = managedRegistrationConfig() ?: return false
        val serverBaseUrl = config.serverBaseUrl
        if (serverBaseUrl.isEmpty() || !isHttpUrl(serverBaseUrl)) {
            return false
        }
        val previous = store.serverBaseUrl()
        store.setServerBaseUrl(serverBaseUrl)
        val current = store.serverBaseUrl()
        if (current == previous) {
            return false
        }
        registrationLog("managed_server_base_url_updated old=$previous new=$current")
        return true
    }

    private fun managedRegistrationConfig(): ManagedRegistrationConfig? {
        val restrictionsManager = getSystemService(RestrictionsManager::class.java) ?: return null
        val bundle = restrictionsManager.applicationRestrictions ?: return null
        return ManagedRegistrationConfig(
            bundle.getString(MC_SERVER_BASE_URL).orEmpty().trim(),
            bundle.getString(MC_IDENTIFIER).orEmpty().trim(),
            bundle.getString(MC_SECRET).orEmpty(),
            bundle.getString(MC_DISPLAY_NAME).orEmpty().trim(),
        )
    }

    private fun registerManagedDevice(config: ManagedRegistrationConfig, attemptKey: String) {
        managedRegistrationInProgress = true
        lastManagedRegistrationAttemptKey = attemptKey
        registrationLog("managed_registration_started identifier=${config.identifier.debugTail()} server=${store.serverBaseUrl()} displayNameLength=${config.displayName.length}")
        setScreen(ScreenState.Loading("登録しています", "管理設定から端末を登録しています。", true))
        executor.execute {
            try {
                val fcmToken = FcmTokenProvider().obtainToken(this)
                store.saveFcmToken(fcmToken)
                val device = ApiClient(store.serverBaseUrl(), "").registerDevice(
                    config.identifier,
                    config.secret,
                    config.displayName,
                    fcmToken,
                )
                store.saveRegistration(device, fcmToken)
                syncApiClientFromStore()
                mainHandler.post {
                    managedRegistrationInProgress = false
                    registrationLog("managed_registration_succeeded identifier=${config.identifier.debugTail()}")
                    showRegistrationDone()
                }
            } catch (e: ApiClient.ApiException) {
                mainHandler.post {
                    managedRegistrationInProgress = false
                    registrationLog("managed_registration_api_error identifier=${config.identifier.debugTail()} httpStatus=${e.httpStatus} code=${e.code}")
                    when (e.code) {
                        "DEVICE_ALREADY_REGISTERED", "REGISTRATION_TOKEN_NOT_FOUND" -> {
                            showRegisterBlocked("管理設定では登録できません", "管理者に新しい DeviceRegistrationToken を依頼してください。")
                        }
                        else -> showRegisterBlocked("登録できませんでした", registrationApiFailureMessage(e))
                    }
                }
            } catch (e: IOException) {
                mainHandler.post {
                    managedRegistrationInProgress = false
                    registrationLog("managed_registration_io_error identifier=${config.identifier.debugTail()} error=${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                    showRegisterBlocked("登録できませんでした", registrationIoFailureMessage(e))
                }
            } catch (_: JSONException) {
                mainHandler.post {
                    managedRegistrationInProgress = false
                    registrationLog("managed_registration_response_parse_error identifier=${config.identifier.debugTail()}")
                    showRegisterBlocked("登録できませんでした", registrationResponseParseFailureMessage())
                }
            } catch (_: Exception) {
                mainHandler.post {
                    managedRegistrationInProgress = false
                    registrationLog("managed_registration_fcm_token_failed identifier=${config.identifier.debugTail()}")
                    showRegisterBlocked("登録できませんでした", "原因: 通知トークンを取得できませんでした。\nFirebase の設定、Google Play 開発者サービス、端末のネットワーク状態を確認してください。")
                }
            }
        }
    }

    private fun showRegistrationDone() {
        setScreen(ScreenState.RegistrationDone)
    }

    private fun showAlreadyRegistered(linkServerBaseUrl: String?) {
        setScreen(ScreenState.AlreadyRegistered(linkServerBaseUrl))
    }

    private fun showRegisterBlocked(heading: String, message: String) {
        activeRegistrationRequestKey = ""
        setScreen(ScreenState.RegisterBlocked(heading, message))
    }

    private fun showRegisterRetryableError(identifier: String, secret: String, displayName: String, fcmToken: String, message: String) {
        setScreen(ScreenState.Register(identifier, secret, displayName, message, fcmToken))
    }

    private fun handleUnconsumedRegistrationDeepLinkOnResume(): Boolean {
        val data = intent?.data ?: return false
        if (!isRegistrationDeepLink(data)) {
            return false
        }
        if (lastHandledRegistrationDeepLink == data.toString()) {
            return false
        }
        handleIntent(intent)
        return true
    }

    private fun isRegistrationDeepLink(data: Uri): Boolean {
        return data.scheme == "apkdist" && data.host == "register-device"
    }

    private fun registrationRequestKey(identifier: String, secret: String, displayName: String, fcmToken: String): String {
        return listOf(store.serverBaseUrl(), identifier, secret, displayName, fcmToken).joinToString("\u0000")
    }

    private fun registrationApiFailureMessage(e: ApiClient.ApiException): String {
        val parts = mutableListOf(
            "原因: サーバーが登録リクエストを拒否しました。",
            "HTTP ${e.httpStatus} / ${e.code}",
        )
        val message = e.message.orEmpty().trim()
        if (message.isNotEmpty()) {
            parts.add("サーバー応答: $message")
        }
        parts.add("接続先: ${store.serverBaseUrl()}")
        return parts.joinToString("\n")
    }

    private fun registrationIoFailureMessage(e: IOException): String {
        val message = e.message.orEmpty()
        val reason = when {
            e is UnknownServiceException || message.contains("CLEARTEXT", ignoreCase = true) -> {
                "HTTP通信が許可されていません。HTTPSのURLを使うか、アプリのHTTP許可設定を確認してください。"
            }
            e is UnknownHostException -> {
                "サーバー名を解決できません。URLのホスト名、DNS、ネットワーク接続を確認してください。"
            }
            e is SocketTimeoutException -> {
                "サーバー応答がタイムアウトしました。サーバーの起動状態、ネットワーク経路、ファイアウォールを確認してください。"
            }
            e is ConnectException || e is NoRouteToHostException -> {
                "サーバーに接続できません。IPアドレス、ポート、端末とサーバーが同じネットワーク上にあるかを確認してください。"
            }
            e is SSLException -> {
                "HTTPS/TLS接続に失敗しました。証明書、サーバーURL、端末時刻を確認してください。"
            }
            else -> {
                "通信エラーが発生しました。ネットワーク状態とサーバーURLを確認してください。"
            }
        }
        return buildRegistrationFailureMessage(reason, e.javaClass.simpleName, message)
    }

    private fun registrationResponseParseFailureMessage(): String {
        return buildRegistrationFailureMessage(
            "サーバーから登録結果を読み取れませんでした。サーバーの応答形式が想定と違います。",
            "JSON_PARSE_ERROR",
            "",
        )
    }

    private fun buildRegistrationFailureMessage(reason: String, detailType: String, detailMessage: String): String {
        val parts = mutableListOf(
            "原因: $reason",
            "接続先: ${store.serverBaseUrl()}",
            "詳細: $detailType",
        )
        if (detailMessage.isNotBlank()) {
            parts[parts.lastIndex] = "詳細: $detailType: $detailMessage"
        }
        return parts.joinToString("\n")
    }

    private fun registrationLog(message: String) {
        Log.i(REGISTRATION_LOG_TAG, message)
    }

    private fun String?.debugTail(): String {
        if (this.isNullOrBlank()) {
            return "(blank)"
        }
        return if (length <= 8) this else take(4) + "..." + takeLast(4)
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = Uri.parse(value.trim())
        val scheme = uri.scheme
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    private fun normalizeBaseUrl(value: String?): String {
        if (value.isNullOrBlank() || !isHttpUrl(value)) {
            return ""
        }
        return value.trim().trimEnd('/')
    }

    private fun syncApiClientFromStore(): Boolean {
        val serverBaseUrl = store.serverBaseUrl()
        val deviceAuthToken = store.deviceAuthToken()
        if (serverBaseUrl == activeServerBaseUrl && deviceAuthToken == activeDeviceAuthToken && ::apiClient.isInitialized) {
            return false
        }
        apiClient = ApiClient(serverBaseUrl, deviceAuthToken)
        activeServerBaseUrl = serverBaseUrl
        activeDeviceAuthToken = deviceAuthToken
        return true
    }

    private fun showHome(forceFetch: Boolean) {
        setScreen(ScreenState.Loading("確認中", "配信設定を確認しています。", true))
        if (forceFetch || store.pendingPolicyFetch() || store.fetchedPolicyJson().isEmpty()) {
            fetchPolicy()
        } else {
            try {
                showPolicy(ApiClient.parsePolicy(store.fetchedPolicyJson()))
                fetchNotificationsQuietly()
            } catch (_: JSONException) {
                fetchPolicy()
            }
        }
    }

    private fun fetchPolicy() {
        showLoading("配信設定を確認しています")
        executor.execute {
            try {
                val snapshot = apiClient.fetchPolicy()
                val notifications = fetchNotificationsOrEmpty()
                store.saveFetchedPolicy(snapshot.rawJson)
                mainHandler.post {
                    displayedNotifications = notifications
                    showPolicy(snapshot)
                }
            } catch (e: ApiClient.ApiException) {
                mainHandler.post {
                    if (e.code == "DEVICE_AUTH_REQUIRED") {
                        showRegisterBlocked("再登録が必要です", "管理者に新しい登録リンクを依頼してください。")
                    } else {
                        showOffline()
                    }
                }
            } catch (_: IOException) {
                mainHandler.post { showOffline() }
            } catch (_: JSONException) {
                mainHandler.post { showOffline() }
            }
        }
    }

    private fun refreshHomeByPull() {
        if (isHomeRefreshing) {
            return
        }
        isHomeRefreshing = true
        store.setPendingPolicyFetch(true)
        executor.execute {
            try {
                val snapshot = apiClient.fetchPolicy()
                val notifications = fetchNotificationsOrEmpty()
                store.saveFetchedPolicy(snapshot.rawJson)
                mainHandler.post {
                    displayedNotifications = notifications
                    showPolicy(snapshot)
                    isHomeRefreshing = false
                }
            } catch (e: ApiClient.ApiException) {
                mainHandler.post {
                    isHomeRefreshing = false
                    if (e.code == "DEVICE_AUTH_REQUIRED") {
                        showRegisterBlocked("再登録が必要です", "管理者に新しい登録リンクを依頼してください。")
                    } else {
                        Toast.makeText(this, "更新できませんでした", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: IOException) {
                mainHandler.post {
                    isHomeRefreshing = false
                    Toast.makeText(this, "通信できる場所で更新してください", Toast.LENGTH_LONG).show()
                }
            } catch (_: JSONException) {
                mainHandler.post {
                    isHomeRefreshing = false
                    Toast.makeText(this, "更新できませんでした", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun registerFcmPolicyReceiver() {
        if (fcmPolicyReceiverRegistered) {
            return
        }
        val filter = IntentFilter(FcmMessagingService.ACTION_POLICY_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fcmPolicyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(fcmPolicyReceiver, filter)
        }
        fcmPolicyReceiverRegistered = true
    }

    private fun unregisterFcmPolicyReceiver() {
        if (!fcmPolicyReceiverRegistered) {
            return
        }
        unregisterReceiver(fcmPolicyReceiver)
        fcmPolicyReceiverRegistered = false
    }

    private fun registerRestrictionsReceiver() {
        if (restrictionsReceiverRegistered) {
            return
        }
        val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(restrictionsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(restrictionsReceiver, filter)
        }
        restrictionsReceiverRegistered = true
    }

    private fun unregisterRestrictionsReceiver() {
        if (!restrictionsReceiverRegistered) {
            return
        }
        unregisterReceiver(restrictionsReceiver)
        restrictionsReceiverRegistered = false
    }

    private fun fetchNotificationsOrEmpty(): List<ApiClient.DeviceNotification> {
        return try {
            apiClient.fetchNotifications()
        } catch (_: IOException) {
            emptyList()
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun notificationsFor(
        snapshot: ApiClient.PolicySnapshot,
        serverNotifications: List<ApiClient.DeviceNotification>,
    ): List<ApiClient.DeviceNotification> {
        val merged = serverNotifications.filterNot { isLocalRequiredInstallNotification(it) }.toMutableList()
        for (notification in localRequiredInstallNotifications(snapshot)) {
            val alreadyPresent = merged.any { existing ->
                existing.kind == notification.kind &&
                    ((existing.appId > 0 && existing.appId == notification.appId) ||
                        (existing.packageName.isNotEmpty() && existing.packageName == notification.packageName))
            }
            if (!alreadyPresent) {
                merged.add(notification)
            }
        }
        return merged
    }

    private fun localRequiredInstallNotifications(snapshot: ApiClient.PolicySnapshot): List<ApiClient.DeviceNotification> {
        return requiredInstallEntries(snapshot).map { entry ->
            val appName = notificationAppName(entry.displayName, entry.packageName)
            ApiClient.DeviceNotification(
                localRequiredInstallNotificationId(entry),
                RequiredAppNotificationReceiver.KIND_INSTALL_PERMISSION_REQUIRED,
                "必須アプリのインストールが必要です",
                "$appName をインストールしてください。",
                snapshot.updatedAt,
                entry.appId,
                entry.packageName,
                appName,
            )
        }
    }

    private fun notificationAppName(displayName: String?, packageName: String): String {
        val name = displayName?.trim().orEmpty()
        return if (name.isNotEmpty()) name else packageName
    }

    private fun notificationText(text: String, notification: ApiClient.DeviceNotification): String {
        if (notification.packageName.isEmpty() || notification.displayName.isEmpty() || notification.displayName == notification.packageName) {
            return text
        }
        return text.replace(notification.packageName, notification.displayName)
    }

    private fun isLocalRequiredInstallNotification(notification: ApiClient.DeviceNotification): Boolean {
        return notification.id < 0 && notification.kind == RequiredAppNotificationReceiver.KIND_INSTALL_PERMISSION_REQUIRED
    }

    private fun localRequiredInstallNotificationId(entry: ApiClient.PolicyEntry): Int {
        return -((entry.packageName + ":" + entry.versionCode).hashCode() and Int.MAX_VALUE) - 1
    }

    private fun requiredInstallEntries(snapshot: ApiClient.PolicySnapshot): List<ApiClient.PolicyEntry> {
        return snapshot.entries.filter { entry ->
            if (!entry.isForceInstalled() || blockingDownloadFor(entry.releaseId) != null) {
                return@filter false
            }
            val installState = appInstallStateFor(entry)
            installActionRequired(entry, installState)
        }
    }

    private fun earlyDownloadEntries(snapshot: ApiClient.PolicySnapshot): List<ApiClient.PolicyEntry> {
        return snapshot.entries.filter { entry ->
            if (downloadFor(entry) != null || blockingDownloadFor(entry.releaseId) != null) {
                return@filter false
            }
            val installState = appInstallStateFor(entry)
            if (!installActionRequired(entry, installState)) {
                return@filter false
            }
            entry.isForceInstalled() || (entry.isAvailable() && installState.installed)
        }
    }

    private fun fetchNotificationsQuietly() {
        executor.execute {
            try {
                val serverNotifications = apiClient.fetchNotifications()
                mainHandler.post {
                    val snapshot = displayedPolicySnapshot
                    displayedNotifications = if (snapshot != null) {
                        notificationsFor(snapshot, serverNotifications)
                    } else {
                        serverNotifications
                    }
                    if (showingPolicyHome && snapshot != null) {
                        showPolicy(snapshot)
                    }
                }
            } catch (_: IOException) {
            } catch (_: JSONException) {
            }
        }
    }

    private fun showPolicy(snapshot: ApiClient.PolicySnapshot) {
        displayedPolicySnapshot = snapshot
        displayedNotifications = notificationsFor(snapshot, displayedNotifications)
        displayedHomeActiveDownloadCount = activeDownloadCount()
        setScreen(ScreenState.PolicyHome(snapshot))
        syncRequiredInstallNotifications(snapshot)
        consumeRequiredInstallOpen(snapshot)
    }

    private fun showOffline() {
        setScreen(ScreenState.Offline)
    }

    private fun showNotifications() {
        setScreen(ScreenState.NotificationsLoading)
        executor.execute {
            val snapshot = displayedPolicySnapshot
            try {
                val serverNotifications = apiClient.fetchNotifications()
                val notifications = if (snapshot != null) {
                    notificationsFor(snapshot, serverNotifications)
                } else {
                    serverNotifications
                }
                mainHandler.post {
                    displayedNotifications = notifications
                    showNotificationList(notifications)
                }
            } catch (e: ApiClient.ApiException) {
                mainHandler.post {
                    if (e.code == "DEVICE_AUTH_REQUIRED") {
                        showRegisterBlocked("再登録が必要です", "管理者に新しい登録リンクを依頼してください。")
                    } else if (snapshot != null) {
                        val notifications = notificationsFor(snapshot, displayedNotifications)
                        displayedNotifications = notifications
                        showNotificationList(notifications)
                    } else {
                        showNotificationsOffline()
                    }
                }
            } catch (_: IOException) {
                mainHandler.post {
                    if (snapshot != null) {
                        val notifications = notificationsFor(snapshot, displayedNotifications)
                        displayedNotifications = notifications
                        showNotificationList(notifications)
                    } else {
                        showNotificationsOffline()
                    }
                }
            } catch (_: JSONException) {
                mainHandler.post {
                    if (snapshot != null) {
                        val notifications = notificationsFor(snapshot, displayedNotifications)
                        displayedNotifications = notifications
                        showNotificationList(notifications)
                    } else {
                        showNotificationsOffline()
                    }
                }
            }
        }
    }

    private fun showNotificationList(notifications: List<ApiClient.DeviceNotification>) {
        setScreen(ScreenState.NotificationList(notifications))
    }

    private fun handleRequiredInstallOpen(intent: Intent) {
        if (!store.isRegistered) {
            showLinkRequired()
            return
        }
        pendingRequiredInstallOpen = RequiredInstallOpen(
            intent.getStringExtra(RequiredAppNotificationReceiver.EXTRA_PACKAGE_NAME).orEmpty(),
            intent.getIntExtra(RequiredAppNotificationReceiver.EXTRA_RELEASE_ID, -1),
            intent.getIntExtra(RequiredAppNotificationReceiver.EXTRA_VERSION_CODE, -1),
        )
        showHome(false)
    }

    private fun consumeRequiredInstallOpen(snapshot: ApiClient.PolicySnapshot) {
        val pending = pendingRequiredInstallOpen ?: return
        val entry = snapshot.entries.firstOrNull {
            it.packageName == pending.packageName ||
                (pending.releaseId > 0 && it.releaseId == pending.releaseId)
        }
        pendingRequiredInstallOpen = null
        if (entry != null) {
            showAppDetail(entry)
        } else {
            Toast.makeText(this, "対象アプリの配信設定を更新してください", Toast.LENGTH_LONG).show()
        }
    }

    private fun showNotificationsOffline() {
        setScreen(ScreenState.NotificationsOffline)
    }

    private fun notificationActionLabel(): String {
        val count = displayedNotifications.size
        return if (count > 0) "通知 $count" else "通知"
    }

    private fun openNotification(notification: ApiClient.DeviceNotification) {
        val entry = policyEntryForNotification(notification)
        if (entry != null) {
            showAppDetail(entry)
        } else {
            Toast.makeText(this, "対象アプリの配信設定を更新してください", Toast.LENGTH_LONG).show()
            showHome(true)
        }
    }

    private fun policyEntryForNotification(notification: ApiClient.DeviceNotification): ApiClient.PolicyEntry? {
        val snapshot = displayedPolicySnapshot ?: return null
        if (!notification.hasApp()) {
            return null
        }
        return snapshot.entries.firstOrNull { entry ->
            (notification.appId > 0 && notification.appId == entry.appId) ||
                (notification.packageName.isNotEmpty() && notification.packageName == entry.packageName)
        }
    }

    private fun policyVersionLine(
        entry: ApiClient.PolicyEntry,
        installState: AppInstallState,
    ): String {
        val target = versionText(entry.versionName, entry.versionCode.toLong())
        if (!installState.installed) {
            return "version $target"
        }
        if (installState.versionCode < entry.versionCode) {
            return "version ${versionText(installState.versionName, installState.versionCode)} → $target"
        }
        return "version $target"
    }

    private fun policyStatusLine(
        entry: ApiClient.PolicyEntry,
        installState: AppInstallState,
        showDownloadStatus: Boolean,
    ): String? {
        val actionRequired = installActionRequired(entry, installState)
        val download = downloadFor(entry)
        if (showDownloadStatus && actionRequired && download != null) {
            return downloadStatusText(download)
        }
        if (actionRequired) {
            return if (installState.installed && installState.versionCode == entry.versionCode.toLong()) {
                "内容に更新あり"
            } else {
                null
            }
        }
        return if (installState.installed) "インストール済み" else null
    }

    private fun appTitle(entry: ApiClient.PolicyEntry): String {
        return if (hasDisplayName(entry)) entry.displayName else entry.packageName
    }

    private fun appInfoLines(
        entry: ApiClient.PolicyEntry,
        installState: AppInstallState,
        showDownloadStatus: Boolean = true,
    ): List<String> {
        val lines = mutableListOf<String>()
        if (hasDisplayName(entry)) {
            lines.add(entry.packageName)
        }
        lines.add(policyVersionLine(entry, installState))
        policyStatusLine(entry, installState, showDownloadStatus)?.let { lines.add(it) }
        return lines
    }

    private fun appInfoText(
        entry: ApiClient.PolicyEntry,
        installState: AppInstallState,
        showDownloadStatus: Boolean = true,
    ): String {
        return appInfoLines(entry, installState, showDownloadStatus).joinToString("\n")
    }

    private fun hasDisplayName(entry: ApiClient.PolicyEntry): Boolean {
        return entry.displayName.isNotBlank() && entry.displayName != entry.packageName
    }

    private fun installActionRequired(entry: ApiClient.PolicyEntry, installState: AppInstallState): Boolean {
        if (!installState.installed) {
            return true
        }
        if (installState.versionCode < entry.versionCode) {
            return true
        }
        if (installState.versionCode == entry.versionCode.toLong()) {
            return entry.artifactSha256.isNotBlank() &&
                installState.apkSha256.isNotBlank() &&
                !entry.artifactSha256.equals(installState.apkSha256, ignoreCase = true)
        }
        return false
    }

    private fun versionText(versionName: String?, versionCode: Long): String {
        if (!versionName.isNullOrBlank()) {
            return versionName
        }
        return "code $versionCode"
    }

    private fun appInstallStateFor(entry: ApiClient.PolicyEntry): AppInstallState {
        return try {
            val info = packageManager.getPackageInfo(entry.packageName, 0)
            AppInstallState(true, packageVersionCode(info), info.versionName ?: "", installedApkSha256(info))
        } catch (_: PackageManager.NameNotFoundException) {
            AppInstallState(false, -1, "", "")
        }
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    private fun installedApkSha256(info: PackageInfo): String {
        return try {
            val sourceDir = info.applicationInfo?.sourceDir ?: return ""
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(sourceDir).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun serverHost(): String {
        val uri = Uri.parse(store.serverBaseUrl())
        return if (uri.host.isNullOrBlank()) store.serverBaseUrl() else uri.host!!
    }

    private fun showAppDetail(entry: ApiClient.PolicyEntry) {
        setScreen(ScreenState.AppDetail(entry))
    }

    private fun installEntry(entry: ApiClient.PolicyEntry) {
        if (!canUseAmapiCustomAppInstall() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            requestInstallPermissionIfNeeded()
            Toast.makeText(this, "不明なアプリのインストールを許可してから再試行してください", Toast.LENGTH_LONG).show()
            return
        }

        val activeDownload = blockingDownloadFor(entry.releaseId)
        if (activeDownload != null) {
            Toast.makeText(this, downloadStatusText(activeDownload), Toast.LENGTH_LONG).show()
            refreshDownloadStatusViews()
            return
        }

        val downloaded = downloadFor(entry)
        if (downloaded != null && downloaded.isDownloaded) {
            installDownloadedEntry(entry, downloaded)
            return
        }

        try {
            ApkDownloadManager().enqueue(this, entry.packageName, entry.releaseId, entry.versionCode)
            Toast.makeText(this, "ダウンロードを開始しました", Toast.LENGTH_LONG).show()
            startDownloadStatusRefresh()
            refreshDownloadStatusViews()
        } catch (_: RuntimeException) {
            showOffline()
            Toast.makeText(this, "ダウンロードを開始できませんでした", Toast.LENGTH_LONG).show()
        }
    }

    private fun installDownloadedEntry(entry: ApiClient.PolicyEntry, download: ApkDownloadStore.PendingApkDownload) {
        val apkFile = File(download.filePath)
        if (!apkFile.isFile || apkFile.length() <= 0) {
            ApkDownloadManager().cleanup(this, download)
            installEntry(entry)
            return
        }

        try {
            downloadStore.markInstalling(entry.releaseId, entry.versionCode, apkFile.length())
            if (canUseAmapiCustomAppInstall()) {
                executor.execute {
                    try {
                        AmapiCustomAppInstaller().install(applicationContext, entry.packageName, apkFile)
                        mainHandler.post {
                            ApkDownloadManager().cleanup(this, download)
                            RequiredAppNotificationReceiver.cancelRequiredInstallNotification(this, entry.packageName, entry.versionCode)
                            Toast.makeText(this, "インストールを開始しました", Toast.LENGTH_LONG).show()
                            refreshDownloadStatusViews()
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        mainHandler.post { installDownloadedWithPackageInstaller(entry, download, apkFile) }
                    } catch (_: Exception) {
                        mainHandler.post { installDownloadedWithPackageInstaller(entry, download, apkFile) }
                    }
                }
                return
            }
            installDownloadedWithPackageInstaller(entry, download, apkFile)
        } catch (_: RuntimeException) {
            Toast.makeText(this, "インストールを開始できませんでした", Toast.LENGTH_LONG).show()
            refreshDownloadStatusViews()
        }
    }

    private fun installDownloadedWithPackageInstaller(
        entry: ApiClient.PolicyEntry,
        download: ApkDownloadStore.PendingApkDownload,
        apkFile: File,
    ) {
        try {
            downloadStore.markInstalling(entry.releaseId, entry.versionCode, apkFile.length())
            ApkInstaller().install(this, entry.packageName, apkFile, apkFile.length(), entry.releaseId, entry.versionCode)
            Toast.makeText(this, "インストール確認を開始しました", Toast.LENGTH_LONG).show()
            startDownloadStatusRefresh()
            refreshDownloadStatusViews()
        } catch (_: IOException) {
            ApkDownloadManager().cleanup(this, download)
            Toast.makeText(this, "インストールを開始できませんでした", Toast.LENGTH_LONG).show()
            refreshDownloadStatusViews()
        } catch (_: RuntimeException) {
            ApkDownloadManager().cleanup(this, download)
            Toast.makeText(this, "インストールを開始できませんでした", Toast.LENGTH_LONG).show()
            refreshDownloadStatusViews()
        }
    }

    private fun uninstallEntry(entry: ApiClient.PolicyEntry) {
        if (canUseAmapiCustomAppInstall()) {
            uninstallEntryWithAmapi(entry.packageName)
            return
        }
        uninstallEntryWithPackageInstaller(entry.packageName)
    }

    private fun uninstallEntryWithAmapi(targetPackageName: String) {
        Toast.makeText(this, "アンインストールを開始しました", Toast.LENGTH_LONG).show()
        executor.execute {
            try {
                AmapiCustomAppInstaller().uninstall(applicationContext, targetPackageName)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                mainHandler.post { uninstallEntryWithPackageInstaller(targetPackageName) }
            } catch (_: Exception) {
                mainHandler.post { uninstallEntryWithPackageInstaller(targetPackageName) }
            }
        }
    }

    private fun uninstallEntryWithPackageInstaller(targetPackageName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val callback = Intent(this, InstallNotificationReceiver::class.java)
                    .setAction(InstallNotificationReceiver.ACTION_UNINSTALL_COMMITTED)
                    .putExtra(InstallNotificationReceiver.EXTRA_PACKAGE_NAME, targetPackageName)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val pendingIntent = PendingIntent.getBroadcast(this, targetPackageName.hashCode(), callback, flags)
                packageManager.packageInstaller.uninstall(targetPackageName, pendingIntent.intentSender)
                Toast.makeText(this, "アンインストールを開始しました", Toast.LENGTH_LONG).show()
                return
            } catch (_: SecurityException) {
                openUninstallIntent(targetPackageName)
                return
            } catch (_: RuntimeException) {
                openUninstallIntent(targetPackageName)
                return
            }
        }
        openUninstallIntent(targetPackageName)
    }

    @Suppress("DEPRECATION")
    private fun openUninstallIntent(targetPackageName: String) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$targetPackageName"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "アンインストール画面を開けませんでした", Toast.LENGTH_LONG).show()
        }
    }

    private fun activeDownloadCount(): Int {
        return downloadStore.all().size
    }

    private fun blockingDownloadFor(releaseId: Int): ApkDownloadStore.PendingApkDownload? {
        val download = downloadStore.findActiveForRelease(releaseId)
        return if (download != null && download.isBlocking()) download else null
    }

    private fun downloadFor(entry: ApiClient.PolicyEntry): ApkDownloadStore.PendingApkDownload? {
        val download = downloadStore.find(entry.releaseId, entry.versionCode)
        return if (download != null && (download.isBlocking() || download.isDownloaded())) download else null
    }

    private fun refreshDownloadStatusViews() {
        downloadRefreshTick += 1
        val activeDownloads = activeDownloadCount()
        val snapshot = displayedPolicySnapshot
        if (snapshot != null) {
            displayedNotifications = notificationsFor(snapshot, displayedNotifications)
            syncRequiredInstallNotifications(snapshot)
        }
        if (showingPolicyHome && snapshot != null && activeDownloads != displayedHomeActiveDownloadCount) {
            showPolicy(snapshot)
        }
        if (activeDownloads > 0 && hasVisibleDownloadUi()) {
            startDownloadStatusRefresh()
        }
    }

    private fun downloadStatusText(download: ApkDownloadStore.PendingApkDownload): String {
        return when (download.state) {
            ApkDownloadStore.STATE_ENQUEUED -> "ダウンロード待機中"
            ApkDownloadStore.STATE_RUNNING -> {
                val percent = download.progressPercent()
                if (percent >= 0) "ダウンロード中 $percent%" else "ダウンロード中"
            }
            ApkDownloadStore.STATE_INSTALLING -> "インストール準備中"
            ApkDownloadStore.STATE_DOWNLOADED -> "ダウンロード済み"
            else -> "ダウンロード処理中"
        }
    }

    private fun downloadActionText(download: ApkDownloadStore.PendingApkDownload): String {
        return if (download.state == ApkDownloadStore.STATE_INSTALLING) "インストール準備中" else "ダウンロード中"
    }

    private fun startDownloadStatusRefresh() {
        if (downloadRefreshScheduled) {
            return
        }
        downloadRefreshScheduled = true
        mainHandler.postDelayed(downloadStatusRefreshRunnable, 1000)
    }

    private fun stopDownloadStatusRefresh() {
        downloadRefreshScheduled = false
        mainHandler.removeCallbacks(downloadStatusRefreshRunnable)
    }

    private fun hasVisibleDownloadUi(): Boolean {
        return showingPolicyHome || screenState is ScreenState.AppDetail
    }

    private fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    private fun syncRequiredInstallNotifications(snapshot: ApiClient.PolicySnapshot) {
        val entries = requiredInstallEntries(snapshot)
        if (canUseAmapiCustomAppInstall()) {
            RequiredAppNotificationReceiver.cancelAllRequiredInstallNotifications(this)
            var started = false
            entries.forEach { entry ->
                try {
                    ApkDownloadManager().enqueue(this, entry.packageName, entry.releaseId, entry.versionCode)
                    started = true
                } catch (_: RuntimeException) {
                }
            }
            earlyDownloadEntries(snapshot).filterNot { it.isForceInstalled() }.forEach { entry ->
                try {
                    ApkDownloadManager().enqueueDownloadOnly(this, entry.packageName, entry.releaseId, entry.versionCode)
                    started = true
                } catch (_: RuntimeException) {
                }
            }
            if (started) {
                startDownloadStatusRefresh()
                refreshDownloadStatusViews()
            }
            return
        }

        var startedDownload = false
        earlyDownloadEntries(snapshot).forEach { entry ->
            try {
                ApkDownloadManager().enqueueDownloadOnly(this, entry.packageName, entry.releaseId, entry.versionCode)
                startedDownload = true
            } catch (_: RuntimeException) {
            }
        }
        if (startedDownload) {
            startDownloadStatusRefresh()
        }

        if (entries.isEmpty()) {
            RequiredAppNotificationReceiver.cancelAllRequiredInstallNotifications(this)
            return
        }

        requestPostNotifications()
        RequiredAppNotificationReceiver.cancelAllRequiredInstallNotifications(this)
        entries.forEach { entry ->
            RequiredAppNotificationReceiver.showRequiredInstallNotification(
                this,
                entry.packageName,
                entry.displayName,
                entry.releaseId,
                entry.versionCode,
            )
        }
    }

    private fun canUseAmapiCustomAppInstall(): Boolean {
        return AmapiCustomAppInstaller().isAvailable(this)
    }

    private fun requestInstallPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun showLoading(message: String) {
        setScreen(ScreenState.Loading(message, "しばらくお待ちください。", true))
    }

    private fun onBack() {
        when (screenState) {
            is ScreenState.AppDetail,
            is ScreenState.NotificationList,
            ScreenState.NotificationsLoading,
            ScreenState.NotificationsOffline -> showHome(false)
            else -> finish()
        }
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Blue,
                onPrimary = Color.White,
                surface = Color.White,
                onSurface = Ink,
                background = Color.White,
                onBackground = Ink,
            ),
            content = content,
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScaffold(screen: ScreenState, tick: Int) {
        val topBar = topBarFor(screen)
        Scaffold(
            containerColor = Color.White,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (topBar != null) {
                    TopAppBar(
                        title = {
                            Text(
                                text = topBar.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        navigationIcon = {
                            if (topBar.showBack) {
                                IconButton(
                                    onClick = ::onBack,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Text("←", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        actions = {
                            if (topBar.notificationActionContentDescription != null && topBar.onNotificationAction != null) {
                                NotificationActionButton(
                                    contentDescription = topBar.notificationActionContentDescription,
                                    showDot = topBar.showNotificationDot,
                                    onClick = topBar.onNotificationAction,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = BarBg,
                            titleContentColor = Ink,
                            actionIconContentColor = Muted,
                            navigationIconContentColor = Ink,
                        ),
                    )
                }
            },
        ) { innerPadding ->
            if (screen is ScreenState.PolicyHome) {
                PullToRefreshBox(
                    isRefreshing = isHomeRefreshing,
                    onRefresh = ::refreshHomeByPull,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .padding(innerPadding),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ScreenContent(screen, tick)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ScreenContent(screen, tick)
                }
            }
        }
    }

    @Composable
    private fun NotificationActionButton(
        contentDescription: String,
        showDot: Boolean,
        onClick: () -> Unit,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_notifications_24),
                    contentDescription = contentDescription,
                    modifier = Modifier.size(24.dp),
                    tint = Muted,
                )
                if (showDot) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .align(Alignment.TopEnd)
                            .background(AlertRed, CircleShape)
                            .border(1.dp, BarBg, CircleShape),
                    )
                }
            }
        }
    }

    private fun topBarFor(screen: ScreenState): TopBarConfig? {
        return when (screen) {
            is ScreenState.Register -> TopBarConfig("APK Instant Deploy")
            is ScreenState.Loading -> if (screen.showTopBar) TopBarConfig("APK Instant Deploy") else null
            is ScreenState.PolicyHome -> TopBarConfig(
                "APK Instant Deploy",
                notificationActionContentDescription = notificationActionLabel(),
                showNotificationDot = displayedNotifications.isNotEmpty(),
                onNotificationAction = ::showNotifications,
            )
            ScreenState.Offline -> TopBarConfig(
                "APK Instant Deploy",
                notificationActionContentDescription = notificationActionLabel(),
                showNotificationDot = displayedNotifications.isNotEmpty(),
                onNotificationAction = ::showNotifications,
            )
            ScreenState.NotificationsLoading,
            is ScreenState.NotificationList,
            ScreenState.NotificationsOffline -> TopBarConfig("通知", showBack = true)
            is ScreenState.AppDetail -> TopBarConfig("", showBack = true)
            else -> null
        }
    }

    @Composable
    private fun ScreenContent(screen: ScreenState, tick: Int) {
        when (screen) {
            ScreenState.LinkRequired -> NoticeScreen(
                success = false,
                heading = "登録リンクが必要です",
                message = "管理者から受け取ったリンクまたはQRコードを開いてください。",
            ) {
                SecondaryButton("閉じる") { finish() }
            }
            is ScreenState.Register -> RegisterScreen(screen)
            ScreenState.RegistrationDone -> NoticeScreen(
                success = true,
                heading = "登録が完了しました",
                message = "この端末でアプリ配信を受け取れます。",
            ) {
                Spacer(Modifier.height(8.dp))
                PrimaryButton("はじめる") {
                    requestPostNotifications()
                    requestInstallPermissionIfNeeded()
                    showHome(true)
                }
            }
            is ScreenState.AlreadyRegistered -> AlreadyRegisteredScreen(screen.linkServerBaseUrl)
            is ScreenState.RegisterBlocked -> NoticeScreen(false, screen.heading, screen.message) {
                SecondaryButton("閉じる") { finish() }
            }
            is ScreenState.Loading -> BodyColumn {
                Title(screen.title)
                BodyText(screen.message)
            }
            is ScreenState.PolicyHome -> PolicyHomeScreen(screen.snapshot, tick)
            ScreenState.Offline -> BodyColumn {
                Title("オフラインで確認できません")
                BodyText("通信できる場所で更新してください。")
                PrimaryButton("更新") { fetchPolicy() }
            }
            ScreenState.NotificationsLoading -> BodyColumn {
                Title("通知を確認しています")
                BodyText("しばらくお待ちください。")
            }
            is ScreenState.NotificationList -> NotificationListScreen(screen.notifications)
            ScreenState.NotificationsOffline -> BodyColumn {
                Title("通知を確認できません")
                BodyText("通信できる場所で再試行してください。")
                PrimaryButton("再試行") { showNotifications() }
            }
            is ScreenState.AppDetail -> AppDetailScreen(screen.entry, tick)
        }
    }

    @Composable
    private fun RegisterScreen(screen: ScreenState.Register) {
        BodyColumn {
            Title("この端末を登録します")
            BodyText("管理者にもこの名前で表示されます。")
            Label("表示名 (必須)")
            OutlinedTextField(
                value = screen.displayName,
                onValueChange = ::updateRegisterDisplayName,
                placeholder = { Text("例: Pixel 8 QA-01") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Blue,
                    unfocusedBorderColor = Line,
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink,
                ),
            )
            Caption(screen.tokenStatus)
            PrimaryButton(
                label = "登録する",
                enabled = screen.displayName.trim().isNotEmpty() && screen.fcmToken.isNotEmpty(),
            ) {
                registerDevice(screen.identifier, screen.secret, screen.displayName.trim(), screen.fcmToken)
            }
        }
    }

    @Composable
    private fun AlreadyRegisteredScreen(linkServerBaseUrl: String?) {
        BodyColumn {
            Title("この端末は既に登録済みです")
            BodyText("アプリを開いて配信状況を確認できます。")
            Spacer(Modifier.height(8.dp))
            PrimaryButton("アプリを開く") { showHome(false) }
            val normalizedLinkServer = normalizeBaseUrl(linkServerBaseUrl)
            if (normalizedLinkServer.isNotEmpty() && normalizedLinkServer != store.serverBaseUrl()) {
                BodyText("このリンクは別のサーバー ($normalizedLinkServer) を指しています。")
                SecondaryButton("サーバーの向き先を変更する") {
                    store.setServerBaseUrl(normalizedLinkServer)
                    syncApiClientFromStore()
                    displayedPolicySnapshot = null
                    displayedNotifications = emptyList()
                    showAlreadyRegistered(normalizedLinkServer)
                }
            }
        }
    }

    @Composable
    private fun PolicyHomeScreen(snapshot: ApiClient.PolicySnapshot, tick: Int) {
        key(tick) {
            if (snapshot.entries.isEmpty()) {
                BodyColumn {
                    Title("ポリシーが未指定です")
                    BodyText("管理者がこの端末向けのアプリ配信を設定すると、ここに一覧が表示されます。")
                    Label("現在のデバイス")
                    HomeInfoList(
                        listOf(
                            "デバイス情報" to if (store.displayName().isEmpty()) "未設定" else store.displayName(),
                            "サーバー情報" to serverHost(),
                            "アプリ情報" to BuildConfig.VERSION_NAME,
                        ),
                    )
                    Caption("最終確認 ${snapshot.updatedAt}")
                }
            } else {
                val activeDownloads = activeDownloadCount()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 24.dp),
                ) {
                    SectionLabel("ポリシーで指定されたアプリ")
                    EntryList(snapshot.entries)
                    if (activeDownloads > 0) {
                        PaddedContent {
                            InfoBanner("ダウンロード ${activeDownloads}件を処理しています")
                        }
                    }
                    SectionLabel("現在のデバイス")
                    HomeInfoList(
                        listOf(
                            "デバイス情報" to if (store.displayName().isEmpty()) "未設定" else store.displayName(),
                            "サーバー情報" to serverHost(),
                            "アプリ情報" to BuildConfig.VERSION_NAME,
                        ),
                    )
                    PaddedContent {
                        Caption("最終確認 ${snapshot.updatedAt}")
                    }
                }
            }
        }
    }

    @Composable
    private fun PaddedContent(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            content = content,
        )
    }

    @Composable
    private fun EntryList(entries: List<ApiClient.PolicyEntry>) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(
                        color = Line,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                val installState = appInstallStateFor(entry)
                PolicyRow(
                    title = appTitle(entry),
                    infoLines = appInfoLines(entry, installState),
                    required = entry.isForceInstalled(),
                    onClick = { showAppDetail(entry) },
                )
            }
        }
    }

    @Composable
    private fun PolicyRow(
        title: String,
        infoLines: List<String>,
        required: Boolean,
        onClick: () -> Unit,
    ) {
        val trailingContent: (@Composable () -> Unit)? = if (required) {
            { StatusChip("必須", BadgeInstallBg, Green) }
        } else {
            null
        }
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            headlineContent = {
                Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    infoLines.forEachIndexed { index, line ->
                        Text(
                            line,
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 2.dp),
                        )
                    }
                }
            },
            trailingContent = trailingContent,
        )
    }

    @Composable
    private fun StatusChip(label: String, containerColor: Color, contentColor: Color) {
        Text(
            label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .background(containerColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }

    @Composable
    private fun NotificationListScreen(notifications: List<ApiClient.DeviceNotification>) {
        if (notifications.isEmpty()) {
            BodyColumn {
                Title("通知はありません")
                BodyText("対応が必要な通知はありません。")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp),
            ) {
                notifications.forEachIndexed { index, notification ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = Line,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                    NotificationRow(
                        notificationText(notification.title, notification),
                        notificationText(notification.body, notification),
                    ) { openNotification(notification) }
                }
                PaddedContent {
                    Caption("対応が完了した通知は自動的に消えます。")
                }
            }
        }
    }

    @Composable
    private fun NotificationRow(title: String, subtitle: String, onClick: () -> Unit) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            headlineContent = {
                Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
        )
    }

    @Composable
    private fun AppDetailScreen(entry: ApiClient.PolicyEntry, tick: Int) {
        key(tick) {
            BodyColumn {
                val installState = appInstallStateFor(entry)
                val download = blockingDownloadFor(entry.releaseId)
                Title(appTitle(entry))
                BodyText(appInfoText(entry, installState))
                val installLabel = if (installState.installed) "アップデート" else "インストール"
                when {
                    installActionRequired(entry, installState) -> {
                        if (entry.isAvailable() && installState.installed) {
                            SecondaryButton("アンインストール") { uninstallEntry(entry) }
                        }
                        PrimaryButton(
                            label = if (download == null) installLabel else downloadActionText(download),
                            enabled = download == null,
                        ) { installEntry(entry) }
                    }
                    entry.isAvailable() -> SecondaryButton("アンインストール") { uninstallEntry(entry) }
                    else -> Caption("最新です")
                }
            }
        }
    }

    @Composable
    private fun NoticeScreen(success: Boolean, heading: String, message: String, actions: @Composable ColumnScope.() -> Unit) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (success) SuccessBg else ErrorBg)
                    .padding(18.dp),
            ) {
                Text(
                    heading,
                    color = if (success) Green else RedDark,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (message.isNotEmpty()) {
                    Text(message, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            HorizontalDivider(color = if (success) SuccessBorder else ErrorBorder)
            BodyColumn(actions)
        }
    }

    @Composable
    private fun BodyColumn(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 20.dp, end = 18.dp, bottom = 24.dp),
            content = content,
        )
    }

    @Composable
    private fun SectionLabel(value: String) {
        Text(
            value,
            color = Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 6.dp),
        )
    }

    @Composable
    private fun Title(value: String) {
        Text(
            value,
            color = Ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 27.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    @Composable
    private fun BodyText(value: String) {
        Text(
            value,
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 23.sp,
            modifier = Modifier.padding(bottom = 14.dp),
        )
    }

    @Composable
    private fun Caption(value: String) {
        Text(
            value,
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
    }

    @Composable
    private fun Label(value: String) {
        Text(
            value,
            color = Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
    }

    @Composable
    private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Blue,
                contentColor = Color.White,
                disabledContainerColor = Color(0xFFE1E3E1),
                disabledContentColor = Color(0xFFE5E5E5),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 8.dp)
                .heightIn(min = 44.dp),
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, if (enabled) Blue else Color(0xFFE1E3E1)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Blue,
                disabledContentColor = BlueDisabled,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
                .heightIn(min = 44.dp),
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun InfoBanner(text: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .height(IntrinsicSize.Min)
                .background(InfoBg),
        ) {
            Spacer(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(Blue),
            )
            Text(
                text,
                color = InfoInk,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
    }

    @Composable
    private fun HomeInfoList(items: List<Pair<String, String>>) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(
                        color = Line,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                HomeInfoItem(label, value)
            }
        }
    }

    @Composable
    private fun HomeInfoItem(label: String, value: String) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 0.dp),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            headlineContent = {
                Text(
                    label,
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                )
            },
            supportingContent = {
                Text(
                    value,
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }

    private sealed interface ScreenState {
        data object LinkRequired : ScreenState
        data class Register(
            val identifier: String,
            val secret: String,
            val displayName: String,
            val tokenStatus: String,
            val fcmToken: String,
        ) : ScreenState
        data object RegistrationDone : ScreenState
        data class AlreadyRegistered(val linkServerBaseUrl: String?) : ScreenState
        data class RegisterBlocked(val heading: String, val message: String) : ScreenState
        data class Loading(val title: String, val message: String, val showTopBar: Boolean) : ScreenState
        data class PolicyHome(val snapshot: ApiClient.PolicySnapshot) : ScreenState
        data object Offline : ScreenState
        data object NotificationsLoading : ScreenState
        data class NotificationList(val notifications: List<ApiClient.DeviceNotification>) : ScreenState
        data object NotificationsOffline : ScreenState
        data class AppDetail(val entry: ApiClient.PolicyEntry) : ScreenState
    }

    private data class TopBarConfig(
        val title: String,
        val showBack: Boolean = false,
        val notificationActionContentDescription: String? = null,
        val showNotificationDot: Boolean = false,
        val onNotificationAction: (() -> Unit)? = null,
    )

    private data class AppInstallState(
        val installed: Boolean,
        val versionCode: Long,
        val versionName: String,
        val apkSha256: String,
    )

    private data class RequiredInstallOpen(
        val packageName: String,
        val releaseId: Int,
        val versionCode: Int,
    )

    private data class ManagedRegistrationConfig(
        val serverBaseUrl: String,
        val identifier: String,
        val secret: String,
        val displayName: String,
    ) {
        fun attemptKey(): String {
            return listOf(serverBaseUrl, identifier, secret, displayName).joinToString("\u0000")
        }
    }

    private companion object {
        val Ink = Color(0xFF1B1B1B)
        val Muted = Color(0xFF565C65)
        val Line = Color(0xFFDFE1E2)
        val Blue = Color(0xFF005EA8)
        val BlueDisabled = Color(0xFF9DBCD4)
        val AlertRed = Color(0xFFD83933)
        val Green = Color(0xFF2E7044)
        val RedDark = Color(0xFFB50909)
        val BarBg = Color(0xFFF7F9FA)
        val SuccessBg = Color(0xFFECF3EC)
        val SuccessBorder = Color(0xFFB4D0B9)
        val ErrorBg = Color(0xFFF8DFE2)
        val ErrorBorder = Color(0xFFF2938C)
        val InfoBg = Color(0xFFE7F6F8)
        val InfoInk = Color(0xFF3D4551)
        val BadgeInstallBg = Color(0xFFECF3EC)
        const val REQUEST_POST_NOTIFICATIONS = 10
        const val REGISTRATION_LOG_TAG = "ApkDeployRegistration"
        const val MC_SERVER_BASE_URL = "server_base_url"
        const val MC_IDENTIFIER = "device_registration_identifier"
        const val MC_SECRET = "device_registration_secret"
        const val MC_DISPLAY_NAME = "display_name"
    }
}
