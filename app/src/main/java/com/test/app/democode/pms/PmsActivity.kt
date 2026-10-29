package com.test.app.democode.pms

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.test.app.democode.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class PmsActivity : AppCompatActivity() {
    private var uiState by mutableStateOf(PmsUiState())

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::requestApkInstall)
    }

    private val packageChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart ?: return
            uiState = uiState.copy(
                event = "${intent.action?.substringAfterLast('.')}：$packageName"
            )
            loadPackages()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    PmsScreen(
                        state = uiState,
                        onRefresh = ::loadPackages,
                        onSelectPackage = ::selectPackage,
                        onLaunchPackage = ::launchPackage,
                        onUninstallPackage = ::uninstallPackage,
                        onResolveWebIntent = ::resolveWebIntent,
                        onChooseApk = ::chooseApk
                    )
                }
            }
        )
        loadPackages()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageChangedReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(packageChangedReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (uiState.packages.isNotEmpty()) {
            loadPackages()
        }
    }

    private fun loadPackages() {
        uiState = uiState.copy(isLoading = true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launcherPackages = packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0)
                ).mapTo(mutableSetOf()) { it.activityInfo.packageName }

                packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PACKAGE_QUERY_FLAGS)
                ).map { info ->
                    info.toAppItem(packageManager, info.packageName in launcherPackages)
                }.sortedBy { it.label.lowercase() }
            }
            val selected = uiState.selected?.packageName
                ?.let { packageName -> result.firstOrNull { it.packageName == packageName } }
            uiState = uiState.copy(
                isLoading = false,
                packages = result,
                launcherCount = result.count(AppItem::hasLauncher),
                selected = selected
            )
        }
    }

    private fun selectPackage(item: AppItem) {
        uiState = uiState.copy(selected = item)
    }

    private fun launchPackage(item: AppItem) {
        val intent = packageManager.getLaunchIntentForPackage(item.packageName)
        if (intent == null) {
            setMessage("${item.label} 没有可启动的 Launcher Activity")
            return
        }
        startActivity(intent)
    }

    private fun uninstallPackage(item: AppItem) {
        if (item.packageName == packageName) {
            setMessage("Demo 不卸载自身，请选择其他应用")
            return
        }
        val intent = Intent(
            Intent.ACTION_DELETE,
            Uri.parse("package:${item.packageName}")
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            setMessage("设备没有可处理卸载请求的系统组件")
        }
    }

    private fun resolveWebIntent() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://developer.android.com")
        ).addCategory(Intent.CATEGORY_BROWSABLE)
        val matches = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
        val labels = matches.take(5).joinToString { it.loadLabel(packageManager).toString() }
        setMessage(
            if (matches.isEmpty()) {
                "IntentResolver 未找到可打开 HTTPS 的 Activity"
            } else {
                "IntentResolver 匹配 ${matches.size} 个 Activity：$labels"
            }
        )
    }

    private fun chooseApk() {
        if (!packageManager.canRequestPackageInstalls()) {
            setMessage("请先允许“安装未知应用”，返回后再次点击选择 APK")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        apkPicker.launch(arrayOf(APK_MIME_TYPE))
    }

    private fun requestApkInstall(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            setMessage("已交给 Package Installer；确认后由 PMS 校验并安装")
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            setMessage("设备没有可处理 APK 的 Package Installer")
        }
    }

    private fun setMessage(message: String) {
        uiState = uiState.copy(event = message)
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val PACKAGE_QUERY_FLAGS =
            PackageManager.GET_PERMISSIONS.toLong() or
                PackageManager.GET_SIGNING_CERTIFICATES.toLong()
    }
}

private data class PmsUiState(
    val isLoading: Boolean = false,
    val packages: List<AppItem> = emptyList(),
    val launcherCount: Int = 0,
    val selected: AppItem? = null,
    val event: String = "等待操作；安装/卸载后可观察 PACKAGE_* 广播"
)

private data class AppItem(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val uid: Int,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val hasLauncher: Boolean,
    val requestedPermissions: List<String>,
    val grantedPermissionCount: Int,
    val signingSha256: String
)

private fun PackageInfo.toAppItem(
    packageManager: PackageManager,
    hasLauncher: Boolean
): AppItem {
    val permissions = requestedPermissions?.toList().orEmpty()
    val grantedCount = requestedPermissionsFlags
        ?.count { it and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
        ?: 0
    val certificate = signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
    val sha256 = certificate?.let {
        MessageDigest.getInstance("SHA-256")
            .digest(it)
            .joinToString(":") { byte -> "%02X".format(byte) }
    } ?: "无"
    val appInfo = checkNotNull(applicationInfo)
    return AppItem(
        label = appInfo.loadLabel(packageManager).toString(),
        packageName = packageName,
        versionName = versionName ?: "未知",
        versionCode = longVersionCode,
        uid = appInfo.uid,
        isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        isEnabled = appInfo.enabled,
        hasLauncher = hasLauncher,
        requestedPermissions = permissions,
        grantedPermissionCount = grantedCount,
        signingSha256 = sha256
    )
}

@Composable
private fun PmsScreen(
    state: PmsUiState,
    onRefresh: () -> Unit,
    onSelectPackage: (AppItem) -> Unit,
    onLaunchPackage: (AppItem) -> Unit,
    onUninstallPackage: (AppItem) -> Unit,
    onResolveWebIntent: () -> Unit,
    onChooseApk: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            BackButton()
            Spacer(modifier = Modifier.height(8.dp))
            BasicText(
                text = "PackageManager / PMS Demo",
                style = TextStyle(
                    color = Color.Black,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        item {
            InfoCard(
                "调用链",
                "App → ApplicationPackageManager → Binder → PackageManagerService\n" +
                    "PMS 再按 user、enabled 状态、flags 和包可见性组装结果。"
            )
        }
        item {
            val summary = if (state.isLoading) {
                "正在通过 Binder 查询 PMS..."
            } else {
                "当前可见 ${state.packages.size} 个包，其中 ${state.launcherCount} 个有 Launcher 入口"
            }
            BasicText(summary, style = bodyStyle())
        }
        item {
            ActionRow(
                actions = listOf(
                    "刷新包列表" to onRefresh,
                    "解析 HTTPS Intent" to onResolveWebIntent
                )
            )
        }
        item {
            PmsButton(text = "选择 APK 并请求安装", onClick = onChooseApk)
        }
        item {
            InfoCard("执行结果 / 包变化广播", state.event)
        }
        state.selected?.let { selected ->
            item {
                PackageDetail(
                    item = selected,
                    onLaunch = { onLaunchPackage(selected) },
                    onUninstall = { onUninstallPackage(selected) }
                )
            }
        }
        item {
            BasicText(
                text = "可见应用（点击查看 PackageInfo）",
                style = TextStyle(
                    color = Color.Black,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        items(state.packages, key = AppItem::packageName) { item ->
            PackageRow(item = item, onClick = { onSelectPackage(item) })
        }
    }
}

@Composable
private fun PackageRow(item: AppItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color(0xFFF7F7F7))
            .padding(12.dp)
    ) {
        BasicText(
            text = item.label,
            style = TextStyle(Color.Black, 15.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicText(
            text = item.packageName,
            style = TextStyle(Color(0xFF555555), 12.sp, fontFamily = FontFamily.Monospace)
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicText(
            text = listOfNotNull(
                if (item.isSystem) "系统应用" else "用户应用",
                if (item.hasLauncher) "Launcher" else null,
                if (!item.isEnabled) "已禁用" else null
            ).joinToString(" · "),
            style = TextStyle(Color(0xFF1565C0), 12.sp)
        )
    }
}

@Composable
private fun PackageDetail(
    item: AppItem,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF90CAF9), RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        BasicText(
            text = "PackageInfo：${item.label}",
            style = TextStyle(Color(0xFF0D47A1), 16.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(8.dp))
        BasicText(
            text = """
                packageName: ${item.packageName}
                version: ${item.versionName} (${item.versionCode})
                uid: ${item.uid}
                enabled: ${item.isEnabled}
                permissions: ${item.grantedPermissionCount}/${item.requestedPermissions.size} 已授予
                signing SHA-256:
                ${item.signingSha256}
            """.trimIndent(),
            style = TextStyle(
                color = Color(0xFF263238),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace
            )
        )
        if (item.requestedPermissions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            BasicText(
                text = "权限声明：\n${item.requestedPermissions.take(5).joinToString("\n")}" +
                    if (item.requestedPermissions.size > 5) "\n..." else "",
                style = TextStyle(Color(0xFF555555), 12.sp, lineHeight = 17.sp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        ActionRow(
            actions = listOf(
                "启动应用" to onLaunch,
                "请求卸载" to onUninstall
            )
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEFF5FF))
            .padding(12.dp)
    ) {
        BasicText(
            text = title,
            style = TextStyle(Color(0xFF174EA6), 15.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(5.dp))
        BasicText(text = body, style = bodyStyle())
    }
}

@Composable
private fun ActionRow(actions: List<Pair<String, () -> Unit>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        actions.forEach { (title, action) ->
            PmsButton(
                text = title,
                onClick = action,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PmsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BasicText(
        text = text,
        modifier = modifier
            .background(Color(0xFF1565C0), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        style = TextStyle(
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    )
}

private fun bodyStyle() = TextStyle(
    color = Color(0xFF263238),
    fontSize = 13.sp,
    lineHeight = 19.sp
)
