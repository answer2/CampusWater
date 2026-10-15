# CampusWater

**中文** | [English](README.en.md)

校园水控设备的第三方安卓客户端。用蓝牙连接水控设备开阀/关阀，走「趣智校园」的服务端做登录、计费与结算，
并在此基础上重写了整套界面（Jetpack Compose + Miuix + 液态玻璃）。

> **这不是官方应用。** 它按公开可复现的方式复用运营方写在小程序里的接口与签名规则。
> 计费、开阀、结算全部由服务端决定，本应用不做任何绕过，也不提供任何"跳过扣费"的能力。
> 使用可能与学校或运营方的服务条款冲突，风险自负；如与官方小程序表现不一致，以官方服务端为准。

## 功能

**用水**

- 扫描附近的蓝牙水控设备，进入设备页控制开阀 / 关阀
- 闲置自动关停倒计时：停止出水后开始计时，到点弹确认框，可选择"继续用水"（重新计时）或"结束用水"
- 超时策略可配：跟随服务端下发的闲置时限，或自定义 1–60 分钟；到点是只提醒还是自动下发关阀指令也可选
- 使用时段拆分显示，当前时段带进度条（已用 / 剩余分钟数）
- 服务端会下发该设备允许的使用时段，不在时段内会提醒但不强制拦截

**后台监控与通知**

- 用水监控跑在前台服务里：退出应用、划掉最近任务都不影响，超时自动关停照常生效
- 用水期间通知栏常驻状态条，带「结束用水」按钮和计时器
- 结束、超时自动关停、开阀失败都会发通知
- 开阀失败 / 设备被占用 / 超时关停会弹应用内横幅，「横幅提醒」可单独关掉（关掉后只静默进通知列表）

**账单与结算**

- 关阀后向服务端查这一单实际结算了多少钱，结束通知里给出本次消费金额与用水时长
- 账单按月查看，支持设备类型筛选、时间/金额排序、金额区间过滤
- 本月合计、近 7 天支出、笔均

**账号**

- 短信验证码登录（未注册的号码服务端会自动建号），也支持密码登录
- 接入身份可切换（部分项目只允许支付宝端接入）
- 项目编号可填写；登录凭据与账号信息只保存在本机
- 登录前需同意《用户协议与声明》

**界面**

- 两套风格可切换：Miuix（HyperOS）与液态玻璃（Apple 那套）
- 液态玻璃下：底部导航浮成玻璃胶囊、顶栏透明模糊、开关是 iOS 样式、弹窗换 Apple alert 排布
- 主题色可选预设，也可以在 HSV 色板上自选任意颜色；支持跟随系统深色、手动深色、纯白浅色主题
- 运行日志集中在个人页查看，可复制或清空

## 构建

环境要求：

| 组件 | 版本 |
| --- | --- |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.3.21 |
| Jetpack Compose | 1.12.0 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| JDK | 17+ |

```bash
# 需要本地 Android SDK，local.properties 里写 sdk.dir
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

两个必须知道的坑（都已在仓库里配好，改的时候别动）：

1. `gradle.properties` 里 `android.builtInKotlin=false` —— AGP 内置的 Kotlin 版本读不了 backdrop 2.0.1 的 2.3 元数据，
   所以退回外部 KGP，`app/build.gradle.kts` 里要显式应用 `kotlin.android` 与 `compose.compiler`。
2. 同一文件里 `android.newDsl=false` —— 外部 KGP 和 AGP 9 的新 DSL 不兼容，模块构建脚本要用经典 DSL。

依赖里 `io.github.kyant0:backdrop` 需要 Compose 1.12 / Kotlin 2.3 这一档，Miuix 0.7.2 虽然是对着
Compose 1.9.3 编译的，但在这个组合下可以正常工作。

## 权限说明

只在实现功能所必需的范围里申请：蓝牙（扫描与连接水控设备）、附近的设备（安卓 12+）、通知（用水期间的常驻状态条与提醒）。
不含统计 SDK，不读取通讯录、定位、相册等无关权限。

## 目录结构

```
app/src/main/java/dev/answer/campuswater/
├── api/         服务端接口、签名、金额与账单解析
├── ble/         蓝牙帧编解码与水控流程控制
├── data/        设置、运行时状态、日志、仓库
├── notify/      通知渠道与通知构建
├── service/     后台用水监控前台服务
└── ui/          Compose 界面（Miuix + 液态玻璃组件）
```

## 版权与许可

Copyright (C) 2026 AnswerDev

本程序是自由软件：你可以再分发之和/或依照由自由软件基金会发布的 GNU 通用公共许可证修改之，
无论是版本 3 的许可证，还是（按你的决定）任何较后的版本。

分发本程序的目的是希望它有用，但没有任何担保；甚至没有对适销性或特定用途适用性的暗示担保。
详见 GNU 通用公共许可证。

你应该随本程序一并收到 GNU 通用公共许可证的副本。若没有，参见 <https://www.gnu.org/licenses/>。

完整条款见 [LICENSE](LICENSE)。

SPDX 标识：`GPL-3.0-or-later`。

### 第三方组件

本项目使用的第三方库各自保留其原有许可证，均不改变本项目的 GPL-3.0 授权：

| 组件 | 许可证 |
| --- | --- |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（backdrop、shapes） | Apache-2.0 |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 |
| Jetpack / JetBrains Compose、Material Color Utilities | Apache-2.0 |
| 支付宝应用支付 SDK | 支付宝官方发布，见支付宝开放平台 |

服务端接口与设备协议归趣智校园（运营方）所有。机制与接口口径参考了开源第三方客户端「淋浴 (LinYu, MIT)」及其贡献者。
