package dev.answer.campuswater.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.answer.campuswater.api.ApiConfig
import dev.answer.campuswater.api.DeviceRecord
import dev.answer.campuswater.api.Session
import dev.answer.campuswater.data.ScanEngine
import dev.answer.campuswater.data.Tone
import dev.answer.campuswater.data.UseTimeRepo
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.icons.useful.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Android 12 以下扫蓝牙设备必须要定位权限，这是系统硬性规定，绕不过。 */
private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * 扫描选设备页。
 *
 * <p>流程与小程序一致：扫到广播名 KLCXKJ-Water → 拿蓝牙地址去服务端换设备档案 →
 * 点设备进入控制页。客户端不保存任何设备业务信息，只提供地址。
 */
@Composable
fun ScanPage(
    padding: PaddingValues,
    bottomExtra: Dp = 0.dp,
    onOpenDevice: (DeviceRecord) -> Unit,
) {
    val context = LocalContext.current
    val state by ScanEngine.state.collectAsState()
    val useTime by UseTimeRepo.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.isNotEmpty() && result.values.all { it }) {
            ScanEngine.start(context)
        }
    }

    Page(padding = padding, bottomExtra = bottomExtra) {
        Section {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Useful.Refresh,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = if (state.scanning) "正在扫描水控设备" else "扫描附近的水控设备",
                    style = MiuixTheme.textStyles.title2,
                    modifier = Modifier.weight(1f),
                )
                if (state.scanning) {
                    CircularProgressIndicator(progress = null, size = 22.dp, strokeWidth = 3.dp)
                }
            }
            Footnote(
                text = state.message.ifBlank {
                    "请先打开手机蓝牙；安卓 12 以下的机型还要打开「定位」开关。" +
                        "走近设备（建议 2 米内），扫到后会自动向服务端查询设备档案。"
                },
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            )
        }

        if (state.scanning) {
            LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
        }

        PrimaryButton(
            text = if (state.scanning) "扫描中…" else "开始扫描",
            onClick = {
                val missing = requiredPermissions().any {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing) {
                    permissionLauncher.launch(requiredPermissions())
                } else {
                    ScanEngine.start(context)
                }
            },
            enabled = !state.scanning,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.devices.isNotEmpty()) {
            Section(title = "发现的设备") {
                state.devices.forEach { device ->
                    DeviceRow(device) { onOpenDevice(device) }
                }
            }
            Section(
                title = "使用时段",
                summary = if (useTime.allowedNow) null else "当前不在时段内，设备可能不出水。",
            ) {
                CardBody {
                    UseTimeList(slots = useTime.slots, loading = useTime.loading)
                }
            }
        } else if (Session.get().projectId.isNullOrBlank()) {
            Section(title = "还差一步") {
                CardBody {
                    Footnote(
                        text = "当前账号还没有绑定学校（缺少项目编号），扫到设备也查不到档案。" +
                            "请到「个人」页填一下项目编号，或先在官方小程序里完成绑定。"
                    )
                }
            }
        }

    }
}

@Composable
private fun DeviceRow(device: DeviceRecord, onClick: () -> Unit) {
    val supported = device.isBluetooth()
    BasicComponent(
        title = if (device.deviceName.isNullOrBlank()) "设备 ${device.devId}" else device.deviceName,
        summary = if (supported) {
            "${ApiConfig.bigTypeName(device.bigTypeId)} · 设备编号 ${device.devId} · " +
                "蓝牙地址 ${device.bleAddress}"
        } else {
            "${ApiConfig.bigTypeName(device.bigTypeId)} · 联网型号，本应用只支持蓝牙水控"
        },
        rightActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
        onClick = if (supported) onClick else null,
        enabled = supported,
    )
}
