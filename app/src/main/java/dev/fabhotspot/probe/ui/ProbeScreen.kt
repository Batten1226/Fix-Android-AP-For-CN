package dev.fabhotspot.probe.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fabhotspot.probe.priv.SuShell
import dev.fabhotspot.probe.probe.CapabilityProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val FILE_NAME = "fabhotspot-report.json"

/** 6 GHz hotspot defaults (WPA3 passphrase must be >= 8 chars). */
private const val KEY_SSID = "ssid"
private const val KEY_PASS = "pass"
private const val DEF_SSID = "Xiaomi13-6G"
private const val DEF_PASS = "fabhotspot6g"

/**
 * 报告落盘位置。
 *
 * 实机踩过的坑：外部存储可能不可用（`getExternalFilesDir` 返回非 null 但目录创建失败，
 * 日志报 "Failed to ensure /storage/emulated/0/Android/data/<pkg>/files"），
 * 因此这里必须检查目录是否真的可用，否则回退到内部 filesDir。
 */
private fun reportFile(context: Context): File {
    val ext = try {
        context.getExternalFilesDir(null)
    } catch (_: Throwable) {
        null
    }
    val dir = if (ext != null && (ext.isDirectory || ext.mkdirs())) ext else context.filesDir
    return File(dir, FILE_NAME)
}

@Composable
fun ProbeScreen(autoRun: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<CapabilityProbe.ProbeOutcome?>(null) }
    var localPath by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 用 StateFlow 承接后台线程的进度，避免跨线程写 Compose state
    val progressFlow = remember { MutableStateFlow("") }
    val progress by progressFlow.collectAsState()

    val runProbe: () -> Unit = {
        if (!running) {
            running = true
            notice = null
            progressFlow.value = "准备中…"
            scope.launch {
                try {
                    val o = withContext(Dispatchers.IO) {
                        CapabilityProbe.run(context) { line -> progressFlow.value = line }
                    }
                    outcome = o
                    // 落盘失败不能让体检结果丢失：报告已在内存里，UI 照常显示
                    localPath = try {
                        val f = reportFile(context)
                        f.writeText(o.json)
                        f.absolutePath
                    } catch (t: Throwable) {
                        "落盘失败(${t.javaClass.simpleName}): ${t.message}"
                    }
                    progressFlow.value = "体检完成"
                } catch (t: Throwable) {
                    progressFlow.value = "失败: ${t.javaClass.simpleName}: ${t.message}"
                } finally {
                    running = false
                }
            }
        }
    }

    LaunchedEffect(autoRun) {
        if (autoRun) runProbe()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text("fab-hotspot 体检 (Phase 1)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "体检部分只读：不写 sysfs、不改任何配置。下面的 6GHz 开关会启停热点。" +
                    "体检唯一写盘的是按钮「导出 JSON」产生的报告文件。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))

            // ---- 6 GHz hotspot toggle (the core deliverable) ----
            Spacer(Modifier.height(10.dp))
            Text("6 GHz Hotspot", style = MaterialTheme.typography.titleSmall)
            Text(
                "Needs the Magisk country module + the LSPosed module. " +
                    "The channel is auto-filled with PSC 37 (6135 MHz). " +
                    "WPA3 needs a passphrase of at least 8 characters.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))

            val prefs = remember { context.getSharedPreferences("hotspot", Context.MODE_PRIVATE) }
            var ssid by remember {
                mutableStateOf(prefs.getString(KEY_SSID, DEF_SSID) ?: DEF_SSID)
            }
            var pass by remember {
                mutableStateOf(prefs.getString(KEY_PASS, DEF_PASS) ?: DEF_PASS)
            }
            var apBusy by remember { mutableStateOf(false) }
            var apOn by remember { mutableStateOf(false) }
            var apStatus by remember { mutableStateOf("hotspot not running") }

            OutlinedTextField(
                value = ssid,
                onValueChange = {
                    ssid = it
                    prefs.edit().putString(KEY_SSID, it).apply()
                },
                label = { Text("SSID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = {
                    pass = it
                    prefs.edit().putString(KEY_PASS, it).apply()
                },
                label = { Text("Passphrase (>= 8 chars)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            val ssidOk = ssid.isNotBlank()
            val passOk = pass.length >= 8

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !apBusy && ssidOk && passOk,
                    onClick = {
                        apBusy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                val cmd = if (apOn) "cmd wifi stop-softap"
                                else "cmd wifi start-softap $ssid wpa3 $pass -b 6 -w 160"
                                val res = SuShell.run(cmd, timeoutMs = 30_000)
                                if (apOn) {
                                    Thread.sleep(2000)
                                    "hotspot stopped"
                                } else {
                                    Thread.sleep(12_000)
                                    val st = SuShell.run(
                                        "dumpsys wifi | grep -o 'SoftApInfo{[^}]*}' | head -1",
                                        timeoutMs = 30_000
                                    )
                                    val line = st.output.lines()
                                        .firstOrNull { it.contains("SoftApInfo{") }?.trim() ?: ""
                                    when {
                                        line.isNotEmpty() -> "running: " + line
                                        res.ok -> "command sent but no SoftApInfo yet, retry"
                                        else -> "failed(exit=" + res.exitCode + "): " +
                                            res.output.trim().take(160)
                                    }
                                }
                            }
                            apStatus = r
                            apOn = r.startsWith("running")
                            apBusy = false
                        }
                    }
                ) {
                    Text(
                        when {
                            apBusy -> "working..."
                            apOn -> "Stop 6 GHz hotspot"
                            else -> "Start 6 GHz hotspot"
                        }
                    )
                }
            }
            Text(
                when {
                    !ssidOk -> "SSID must not be empty"
                    !passOk -> "passphrase must be at least 8 characters"
                    else -> apStatus
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running, onClick = { runProbe() }) {
                    Text(if (running) "运行中…" else "开始体检")
                }

                OutlinedButton(
                    enabled = outcome != null && !running,
                    onClick = {
                        val o = outcome ?: return@OutlinedButton
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) {
                                val f = reportFile(context)
                                f.writeText(o.json)
                                val r = SuShell.run(
                                    "mkdir -p /sdcard/Download && cp '${f.absolutePath}' " +
                                        "/sdcard/Download/$FILE_NAME && chmod 644 /sdcard/Download/$FILE_NAME",
                                    timeoutMs = 20_000
                                )
                                if (r.ok) "已导出: /sdcard/Download/$FILE_NAME"
                                else "导出失败(exit=${r.exitCode}): ${r.output.take(200)}" +
                                    "\n本地副本: ${f.absolutePath}"
                            }
                            notice = msg
                        }
                    }
                ) { Text("导出 JSON") }

                OutlinedButton(
                    enabled = outcome != null && !running,
                    onClick = {
                        val o = outcome ?: return@OutlinedButton
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "fab-hotspot 体检报告")
                            putExtra(Intent.EXTRA_TEXT, o.text)
                        }
                        context.startActivity(Intent.createChooser(send, "分享体检报告"))
                    }
                ) { Text("分享文本") }
            }

            if (running) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(progress, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }

            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            localPath?.let {
                Spacer(Modifier.height(4.dp))
                Text("本地副本: $it", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(10.dp))

            SelectionContainer {
                Text(
                    text = outcome?.text
                        ?: "点「开始体检」运行只读探测。\n\n首次会弹 Magisk 授权框，允许即可。",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
