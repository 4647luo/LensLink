# Douk · 尼康相机遥控 App

改版方向：以开源项目 **LutCameraPro**（Apache-2.0）的尼康 PTP 协议层为底座，重做客户端信息架构为「设备 / 控制 / 图库 / 设置」四页。

## 页面结构

### 1. 设备（DeviceScreen）
- 顶部三个入口：添加设备（未连接过的）、刷新搜索（已连接过的）、使用手册
- 未连接：整块空状态卡，中间是「相机实物照片」占位（可在设置中替换），下方是「添加设备」主按钮与三种连接方式说明
- 已连接：绿色状态点 + 连接方式；相机实物照片卡；2×2 信息格（电池电量、存储容量、相机型号、镜头型号）；「进入控制面板」；「断开连接」

### 2. 添加设备（AddDeviceScreen）
- 四种连接方式单选：Wi-Fi STA、Wi-Fi AP 直连、USB PTP、蓝牙（仅 GPS 推送）
- 每种方式下方展示相机端对应设置
- 固定的相机菜单提示：连接至智能设备（Wi-Fi）、连接至计算机（二代机走 USB）
- 「搜索相机设备」→ 扫描结果列表 → 点击即连接并跳转控制页

### 3. 控制（ControlScreen）
- 顶部：视频 / 拍摄 分段切换
- 预览区：16:9 相机画面占位；右上角全屏；右下角「隐藏」；隐藏后折叠为一条可点击恢复的横条
- 全屏：黑底全屏预览 + 左上退出 + 底部机身/镜头信息
- 参数区按行排列（左名称、右参数值、右侧箭头）：
  - 曝光组：ISO 感光度、快门速度、光圈、曝光补偿
  - 对焦与画质组：测光模式、对焦模式、AF 区域、减震模式、色调模式、优化校准
  - 点击任意行弹出可选值列表，选择后立即回写
- 底部：快门条（计数、橙色快门按钮、AF/REC 副按钮）

### 4. 图库（GalleryScreen）
- 小米相册式白底布局：左上小图标打开来源菜单，右上筛选
- 来源三个标签：相机内 / 应用内 / 手机内
- 相机未连接时给出空状态提示；已连接展示按日期分组的 3 列网格
- 点击任意缩略图打开预览弹层
- 筛选弹层：全部 / 仅 JPG / 仅 RAW / 竖构图 / 横构图 / 已回传

### 5. 设置（DoukSettingsScreen）
- 设备：相机实物照片（替换入口）、默认连接方式、蓝牙 GPS 推送开关
- 控制面板：参数排列顺序（可逐项上移/下移，实时生效）、光圈预览开关
- 图库：自动导入相机照片、保存到手机相册
- 关于：应用名、版本、开源归属

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- compileSdk / targetSdk 34，minSdk 29
- Gradle 8.11.1 + AGP 8.5.x + Kotlin 1.9.x
- 协议层保留自 LutCameraPro：`network/NikonPtpClient.kt`（PTP/IP 配对、Live View、属性读写）、`network/UsbPtpClient.kt`（USB PTP）、`network/NikonDiscovery.kt`（网络发现）、`network/PtpIpWire.kt`（报文编解码）

## 当前状态与下一步

- 本轮已完成：四页信息架构与完整交互骨架（连接状态、参数选择、预览显隐/全屏、图库分组与筛选、参数排序）。
- 界面数据目前为演示状态（`DoukState`），真实相机数据尚未写入。
- 下一步接线顺序：
  1. `DeviceScreen` 的「刷新搜索」接 `NikonDiscovery`（Wi-Fi）与 USB 设备广播（USB）
  2. 「添加设备」搜索接 `NikonDiscovery.discover()` 与 `UsbPtpClient` 的 attach 回调
  3. 连接态电量/容量/机身/镜头接 PTP `DeviceInfo`（`GetDeviceInfo`）
  4. 控制页参数行接 `NikonPtpClient` 的 `GetDevicePropValue/SetDevicePropValue`（ISO 0x500F、快门、光圈 0x5007、曝光补偿 0x5010、测光 0x500B、对焦模式 0x500A、AF 区域 0x5012 等）
  5. 预览接 Live View 帧流（JPEG 序列 → Bitmap）

## 许可与归属

- 底座项目 LutCameraPro：Apache-2.0，保留 `LICENSE`、`THIRD_PARTY_NOTICES.md`。
- 发布前需在 App 内提供开源许可页；Nikon / Z 系列为尼康商标，本项目与尼康公司无关联。

## 本轮接线进度（2026-11-15 19:56，已编译并打包 APK）

### 已经接上真实 PTP（不再是演示数据）

| 位置 | 真实来源 |
| --- | --- |
| 添加设备页搜索 | `DoukState.searchDevices()` → `NikonViewModel.discoverCameras()`，走 mDNS `_ptp._tcp` |
| 连接 | `vm.connect(context, ip)` → `NikonPtpClient.startNikonWiWatch()`，15740 端口 PTP/IP 会话（含 InitFail 配对） |
| 手动连接 | 新增「直接连接该 IP」输入框，适配相机不广播的情况（AP 默认 192.168.1.1） |
| 设备页电量 | 属性 0x5001 BatteryLevel |
| 设备页存储 | GetStorageIDs(0x1004) + GetStorageInfo(0x1005) 的 MaxCapability / FreeSpaceInBytes |
| 设备页镜头 | 属性 0x5008 FocalLength（尼康按 1/100mm 上报） |
| 控制页取景 | `NikonPtpClient.liveViewBitmap`（0x9201 start / 0x9203 取帧） |
| 控制页快门 | `triggerCapture()` = 0x9207 |
| 控制页对焦 | `triggerAf()` = 0x90c1 |
| 控制页录像 | `toggleMovieRecord()` = 0x920a / 0x920b |
| ISO / 快门 / 光圈 | 0x500F / 0x500D / 0x5007，读 GetDevicePropDesc，写 SetDevicePropValue |
| 曝光补偿 / 测光 / 对焦模式 | 0x5010 / 0x500B / 0x500A，同样读描述再写值 |
| 图库「相机内」 | `loadStoragePhotos()` → GetObjectHandles(0x1007) 枚举机身照片 |

读不到的项一律显示「未上报 / 机身菜单控制」，不再用假数字充数。

### 尚未接入（下一步要做）

1. USB PTP：底座里 `UsbPtpClient` 由 `WiredViewModel` 驱动，本轮没有搬进 Douk 界面；选 USB 时会提示改用 Wi-Fi。
2. 蓝牙 GPS：未接 `BluetoothAdapter` 向机身推送定位。
3. 减震(VR) / 色调 / 优化校准：尼康未在 PTP 公开写入通道，界面显示「机身菜单控制」。
4. 图库「相机内」目前是文件名列表，缩略图网格与「应用内 / 手机内」真实读取待做。

### IDE 侧 JVM 问题的处理

- 项目 `.gradle/config.properties` 的 `java.home` 原来指向 IDE 自带 jbr（Java 25），Gradle 8.11.1 不认。
- 已改成本机 JDK 17：`C:\Users\LUO\AppData\Local\Programs\Microsoft\jdk-17.0.10.7-hotspot`（原文件备份为 `config.properties.bak`）。
- 另外 IDE 已把 JBR 21 下载到 `C:\Users\LUO\.jdks\jbr-21.0.11`，弹窗里的「Use JVM 21」现在也是可用的。
## USB PTP 与蓝牙 GPS 接入（2026-11-15 20:16，已打包 APK）

前面「尚未接入」清单里的前两项，本轮已经做完并编译打包。

### 一、USB PTP（相机菜单「连接至计算机」，二代 Z 机型）

复用工程自带 `network\UsbPtpClient.kt`（裸 USB 收发 PTP 报文，不经过系统 MTP），新增一层 UI 连接器：

| 环节 | 实现 |
| --- | --- |
| 枚举设备 | `DoukUsbConnector.looksLikeCamera()`：尼康 VID `0x04B0`，或接口类为 STILL_IMAGE(6)/VENDOR_SPEC(255) |
| 系统授权 | `UsbManager.requestPermission` + `PendingIntent` 广播（Android 11+ 必须），授权后自动建会话 |
| 建会话 | `UsbPtpClient.connectDevice()`：扫 bulk IN/OUT + interrupt IN 端点 → `claimInterface` → `OpenSession(0x1002)` |
| 取景 | 连上后自动 `startLiveView()`（尼康 `0x9201` 启动 + `0x9203` 取帧） |
| 拍摄/对焦/录像 | 大按钮 → `triggerCapture()`(0x9207)、AF → `triggerAf()`(0x90c1)、视频 → `toggleMovieRecord()`(0x920a/0x920b) |
| 热插拔 | 注册 `USB_DEVICE_ATTACHED/DETACHED` 广播，插入自动连、拔出自动断 |

UI 落在「添加设备」页的 USB 卡片：状态文字、连接/断开按钮、以及「枚举并连接 USB 相机」主按钮。连上后自动进控制页，画面来源在 `ControlScreen` 里按 `state.usbConnected` 在 USB 与 Wi-Fi 之间切换。

USB 通道目前**不读机身属性**（`GetDevicePropDesc` 那套没在 USB 侧实现），所以设备页电量/存储显示「USB 未上报」，型号取 USB 描述符里的产品名 —— 不编数字。

### 二、蓝牙 GPS 推送（相机菜单「连接至智能设备」的蓝牙智能设备模式）

新增两个文件：

- `network\nikon\ble\NikonBleProtocol.kt` —— 协议常量与载荷编码
- `network\nikon\ble\NikonBleGpsClient.kt` —— 扫描 / GATT / 握手 / 绑定 / 持续推送

**协议事实（来自公开逆向成果，代码为独立重写，未复制他人源码）**

| 项 | 值 |
| --- | --- |
| 基础 UUID | `0000xxxx-3dd4-4255-8d62-6dc7b9bd5561` |
| 主服务 | `0xDE00`（扫描时也靠它识别尼康相机） |
| PAIR `0x2000` | 配对握手，indication，17 字节消息 |
| ID `0x2002` | 控制器名，32 字节 ASCII 补零 |
| GEO `0x2007` | 41 字节 GPS 载荷，write |
| TIME `0x2006` | 10 字节时间载荷，write |
| NOT1 `0x2008` | 状态通知，握手成功后回 `01 00` |
| 加密 | Blowfish/ECB/NoPadding，密钥 `FF FF AA 55 11 22 33 00` |
| 散列 | 自定义 CBC-MAC，初值 left=0x01020304 / right=0x05060708，按 32 位大端字串接 |

**握手 4 阶段**（`NikonPairingSession` 那套流程的简化实现）

1. 我们写阶段 1：`stage=1`、随机时间戳、device（最低字节必须 0x01）、nonce
2. 相机回阶段 2（挑战），我们用 8 组盐反推，命中者构造阶段 3 应答
3. 相机回阶段 4（byte 9..16 是 8 位 ASCII 序列号）
4. 相机在 NOT1 回 `01 00` → 写 32 字节控制器名 → 检查系统蓝牙绑定 → 就绪

**算法已用真机抓包向量验证**：以 Z50II 实测 stage1/stage2 为输入，本实现命中 salt=6，输出 stage3 `device=0x79f1ad53`、`nonce=0x23838a35`，与公开测试向量完全一致。验证脚本 `_tmp\ref\MacTest.java`（同仓库 `_tmp\ref\nsg-master` 内含参考工程）。

**41 字节 GEO 载荷布局**

`header 0x007F` → 纬度方向/度/分/分小数2位 → 经度方向/度/分/分小数2位 → 卫星数 → 海拔基准 P/M → 海拔 → 年(UTC)/月/日/时/分/秒/百分秒 → 有效标志 0x01 → `"WGS-84"` → 10 字节补零。

**推送节奏**：定位变化即写 GEO；每 60 秒补写一次 TIME（本地时间 + 夏令时标志 + 时区偏移）。定位用 `LocationManager` 的 GPS + NETWORK 双 provider。

**绑定**：相机侧需要系统蓝牙绑定。本实现先查系统已配对同名设备；没有则对 BLE 地址 `createBond()`，失败再退回经典蓝牙发现按名字匹配后绑定；绑定完成后断开 GATT 重连一次（相机此时才允许写 GEO）。

**权限**（已写进 `AndroidManifest.xml`）：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH`/`BLUETOOTH_ADMIN`（≤30）、`ACCESS_FINE_LOCATION`（这条已去掉原来的 `maxSdkVersion=32` 限制，否则 Android 13+ 拿不到 GPS）。运行时权限在「添加设备」页点扫描时弹出。

### 三、许可证处理

参考实现的授权不同：`gkoh/furble` 是 MIT，`hurui200320/nsg` 是 AGPL-3.0，`Octopustank/HarmoNiLink` 是 MulanPSL-2.0。**没有把 AGPL 代码复制进本工程**，只按协议事实（UUID、密钥、字段布局、握手阶段）独立实现，本工程继续保持 Apache-2.0，可以二次开发后发布。

### 四、本轮验证路径

1. `:app:compileDebugKotlin` BUILD SUCCESSFUL（build9 / build11 日志）
2. `:app:assembleDebug` BUILD SUCCESSFUL → `app-debug.apk` 58,369,643 字节（2026-11-15 20:16:54）
3. 握手算法用真机向量对拍通过（见上）

### 五、还没做的（如实列出）

1. USB 侧机身属性（电量/存储/ISO 等）未走 `GetDevicePropDesc`，设备页显示「USB 未上报」。
2. USB 图库未做 `GetObjectHandles` 枚举，控制页/图库仍以 Wi-Fi 通道的照片列表为准。
3. 蓝牙 GPS 未做前台服务保活，App 被系统冻结后推送会停（相机休眠唤醒需相机端开「照相机关闭时发送」）。
4. 图库「相机内」仍是文件名列表，无缩略图网格；「应用内 / 手机内」仍占位。
5. 真机未插：本机无 `adb` 连接设备，以上均为编译级 + 向量级验证。

### 六、上手步骤（真机）

USB：相机菜单 →「连接至计算机」→ USB-C 数据线接手机（OTG）→ App 添加设备页选 USB PTP → 点「连接 USB 相机」→ 系统弹窗点允许 → 自动进控制页。

蓝牙 GPS：相机菜单 →「连接至智能设备」→ 打开蓝牙配对模式 → App 选蓝牙 → 点「扫描相机」→ 出现相机条目后点击 → 系统配对弹窗确认 → 状态变「已连接，正在推送定位」，相机「位置数据(智能设备)」里能看到坐标。
### 七、一个必须先知道的相机端前提

如果这台相机**以前用 SnapBridge 配对过**，相机的蓝牙只认 SnapBridge 那套身份（16 位十六进制 device id + 固定 nonce）。nsg 之所以能无缝切换，是靠它自带的 `snapbridge-id-extractor` 反推出 SnapBridge 的身份。

本工程没做这个反推，所以第一次用 Douk 推 GPS 时请按下面任一方式处理：

1. 相机菜单 →「连接至智能设备」→「已配对设备」→ 删除 SnapBridge 的记录，再用 Douk 重新配对（最干净）；
2. 或者在相机里新建一个配对位：连过一次 Douk 之后，Douk 会把自己的 device/nonce 存进 `SharedPreferences("douk_nikon_ble")`，之后点「连接上次」可以直接复用，不用再走一遍配对。

对应地，App 侧如果握手失败，日志会明确写「8 组盐都不匹配（相机可能已与 SnapBridge 绑定了别的身份）」，不会静默失败。
## 缺失项补齐（2026-11-15 20:32，第二版 APK）

上一节列的 5 条缺失，这一轮往下推进了 4 条，第 3 条改成前台服务保活。

### 1. USB 侧机身状态：已接真实属性

`network\UsbPtpClient.kt` 新增：

| 方法 | PTP 操作 | 说明 |
| --- | --- | --- |
| `readBatteryLevel()` | GetDevicePropValue `0x1015` + 属性 `0x5001` | 单字节，直接是百分比 |
| `readIso()` | 属性 `0x500F` | 4 字节按 UINT32、2 字节按 UINT16 解，超出 1..1000000 视为无效 |
| `readStorage()` | GetStorageIDs `0x1004` + GetStorageInfo `0x1005` | PTP1.1 偏移 6=最大容量、14=剩余字节、22=剩余张数 |
| `requestData(opCode, params)` | 通用 | 发命令→取 Data 容器→剥掉 12 字节容器头 |

设备页在 USB 模式下现在显示真实电量、真实「剩余/总容量 GB」，控制页曝光区的 ISO 显示真实值（`state.usbOnly` 分支）。

### 2. USB 图库：已接对象枚举与缩略图

| 方法 | 操作 | 用途 |
| --- | --- | --- |
| `listObjects(limit=200)` | GetObjectHandles `0x1007`（storageID, 0, 0xFFFFFFFF）→ 逐个 GetObjectInfo `0x1008` | 文件名 / 大小 / 修改日期 / 是否 RAW |
| `getThumbnail(handle)` | GetThumb `0x100A` | 取出 JPEG 缩略图，前面有多余字节时按 SOI `FFD8` 定位 |
| `parsePtpString` / `ptpStringLength` | — | PTP 字符串 = 1 字节长度 + UTF‑16LE，ObjectInfo 从偏移 52 开始 |

图库页「相机内」现在按通道分流：USB 走 `state.usbPhotos` + `GetThumb`，Wi‑Fi 走 `state.cameraPhotos` + `0x100A`（`NikonPtpClient` 也补了 `readThumbnail`）。

### 3. 图库整体改成三列网格 + 缩略图

`GalleryScreen.kt` 重写：

- 相机内 / 应用内 / 手机内 三种来源都渲染成三列网格，底条显示文件名，缩略图按 `handle` 缓存到 `mutableStateMapOf<Int, ImageBitmap>`，只在第一次进入该格时请求。
- **应用内**：真实扫描 `Pictures/Douk` 与 `DCIM/Douk`（控制页下载的照片落这两处），`BitmapFactory` 按 400px 目标边长降采样。
- **手机内**：真实走 MediaStore（`MediaStore.Images.Media`，按 DATE_ADDED 倒序，最多 300 条），缩略图用 `ContentResolver.loadThumbnail`（API 29+）。
- 取不到缩略图时显示 JPG/RAW 字块，不假装有图。

### 4. 蓝牙 GPS：加了前台服务保活

新增 `network\nikon\ble\DoukBleKeepAliveService.kt`（`foregroundServiceType=connectedDevice`，通知渠道 `douk_ble_gps`）。

`DoukRoot` 监听 BLE 的 `Phase`：进入 `READY` 就 `startForegroundService`，离开 `READY`（断开/失败）就 `stopService`。这解决的是「App 切后台后进程被冻结、GATT 写入停住」的问题；进程被用户手动划掉则连接仍会断，这点无法绕过（尼康侧要求 GATT 连接由 App 持有）。

### 5. 仍缺的（更新后）

1. USB 侧的 `GetDevicePropDesc(0x1014)` 没有解析，所以 USB 模式下 ISO 之外（快门/光圈/测光/优化校准）只能显示「以机身设置为准」，不能列档位、不能写。
2. USB 侧的 `GetObject(0x1009)` 下载整张照片没做（`readPtpDataStream` 对单次数据上限 5MB，24MP JPEG 会超；要做得分块读）。所以 USB 模式下「应用内」图库暂时看不到从 USB 相机下载的新照片。
3. 快门速度 `0x500D`、光圈 `0x5007` 的尼康编码（APEX/千分制）没有真机核对，不敢直接解码成 1/250、f2.8，宁可显示未上报。
4. 蓝牙配对若相机原本绑过 SnapBridge，仍需先在相机「已配对设备」里删掉旧记录（见上一节）。

### 6. 真机安装受阻（需要手动开一个开关）

adb 已识别设备：`23043RP34C` / Android 14 (SDK 34) / 序列号 `87159031`。但 `adb install` 一直被 MIUI 拦：

```
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

已经试过且都无效：`verifier_verify_adb_installs=0`、`install_non_market_apps=1`、`adb_install_need_confirm=0`、`adb push` + `pm install`。

真正卡住的是 HyperOS 的「USB 安装」总开关，它需要：设置 → 更多设置 → 开发者选项 → **USB 安装**（开启通常要登录小米账号，部分机型还要求插 SIM 卡）。

快捷替代：APK 已经推到 `/sdcard/Download/Douk.apk`，在平板「文件管理 → 下载」里点开也能装（只需允许「未知来源安装」）。
## 第三轮：GitHub 再调研 + 取景链路修正（2026-11-15 20:47）

### 一、又找到的同类项目

| 仓库 | 语言/授权 | 对本项目的价值 |
| --- | --- | --- |
| `AchooLuv/NikonLink` | Kotlin，无 license 声明 | **最有价值**：Z50II 固件 C 1.11 实测的尼康指令表，含取景链路、参数编码、分块下载、断点续传 |
| `laoziocean-code/z-cloud-ai` | Kotlin，MIT | Z30 USB-OTG 直写 Picture Control C1–C9，USB 属性写入参考 |
| `Pinksert/NikonD300Controller` | Kotlin/C++，MIT | USB OTG PTP 控制 D300：实时取景 + 曝光调节 + 相册管理 |
| `18818474455/camera-connect-sdk` | Kotlin/Flutter | 多品牌（佳能/索尼/尼康/松下）USB 相机 SDK，可对比接口抽象 |
| `Zephyrion-Yuan/NikonCopy` | Kotlin | USB OTG MTP/PTP 直接拷 NEF/JPG/MP4 |
| `julianwambach403-arch/nikon-autofocus` | Kotlin | D3400 USB/PTP 锐度监控 |
| `subhashraveendran/aero-shutter` | Go，MIT | 尼康 PTP/IP 传图 CLI + 移动端，NikonLink 的协议来源 |
| `DethCount/ptpip-d5300` | Python，MIT | PTP/IP 早期实现，DSLR 世代对照 |

NikonLink 没有 license 文件，**没有复制它任何代码**，只采纳它公开文档里「实测通过」的协议事实来修正本工程的指令序列 —— 与本工程 Apache-2.0 不冲突。

### 二、按实测指令表修正的取景链路（关键）

原来的实现是 `0x9201` 之后直接循环 `0x9203`。文档记录 Z50II 对 `0x9203` 一律回 `0xA00B`，这就是「连上了但看不到画面」的根因。现在两端都改成：

```
0x9435(1) 进主机控制 → 0x90C2(1) → 属性 0xD1A6=0 → 0x9201 StartLiveView
  → 轮询 0x90C8 到就绪（≤2s）→ 循环 0x9428 GetLiveViewImageEx（Z 系）
  → 0x9428 非 OK 时自动降级 0x9203（老机型）
退出：0x9202 EndLiveView + 0x90C2(0) 交还机身
```

落点：
- `NikonPtpClient.runLiveView()` 新增 `enterHostControl()` / `leaveHostControl()`，帧操作码改成可变 `frameCommand`（0x9428 ↔ 0x9203 互降），`0xA00B` 分支也会换操作码再试。
- `UsbPtpClient.startLiveView()` 同样先 `enterHostControl()`（0x9435/0x90C2/0xD1A6），再 `0x9201` + 轮询 `0x90C8`；`fetchUsbLiveViewFrame()` 用 `frameCommand`，收到非 DATA 容器就切到 `0x9203`；`stopLiveView()` 补 `0x90C2(0)`。

### 三、大缩略图（0x90C4）

图库缩略图现在优先用尼康厂商操作 `0x90C4`（约 1600×1200），失败才回落 `GetThumb 0x100A`（只有 160×120 左右）。Wi‑Fi 与 USB 两条通道都接：`NikonPtpClient.readBigThumbnail()` / `UsbPtpClient.getBigThumbnail()`，`DoukState` 里统一 `decodeThumb()` 降采样到 2 倍。

### 四、USB 分块下载（绕开 5 MiB 单包上限）

新增 `UsbPtpClient.copyObjectToFile(handle, target, expectedSize)`：用 `GetPartialObject 0x101B` 每次取 1 MiB 追加写盘，直到取满 `ObjectCompressedSize` 或返回不足一块。24MP 的 JPG（8–12 MB）也能取下来。

配套：
- `DoukState.saveUsbPhoto(context, handle, fileName)` —— 先落 `cacheDir` 临时文件，再通过 `MediaStore.Images` 插入 `Pictures/Douk`（`IS_PENDING` 两段式写，Android 10+ 免存储权限），成功后 `galleryRefreshTick++`。
- 图库预览弹窗现在显示文件名/大小/格式，USB 来源的照片多一个「保存到手机（Pictures/Douk）」按钮，保存结果直接显示在弹窗里。

### 五、拍完自动刷新图库

`DoukState.shoot()` 静态拍照后延时 1.8s 自动重拉照片列表（USB 走 `loadUsbPhotos()`，Wi‑Fi 走 `loadCameraPhotos()`），避免新照片还没进 `GetObjectHandles` 就刷新导致「拍了但图库没变」。

### 六、确认没写错的两处编码

对照文档核对，本工程的解码其实是对的，不改：
- 快门 `0x500D` = 0.1 ms（1/10000 s）→ `formatShutterRaw` / `parseShutterRaw` 用的就是 10000 分母，80 → 1/125s ✓
- 光圈 `0x5007` = f×100 → `F${v/100}` ✓
- 曝光补偿 `0x5010` = s16，1/1000 EV ✓（已有 `decodeExposureComp`）
- 快门**只读**（写入返回 `0x201C`），文档确认；当前写入失败会走既有失败提示。

### 七、这一版待真机验证的清单

1. 控制页画面是否出现（关键：看 `0x9435`/`0x90C2` 有没有被拒，若被拒会自动跳过，帧操作码会降级）。
2. 设备页电量 / 容量是否显示真实数字（USB 属性读取）。
3. 图库「相机内」是否出现缩略图网格；点开能否「保存到手机」。
4. 图库「手机内」是否能列出本机相册（需要相册权限）。
5. 蓝牙 GPS 前台服务通知是否出现，相机「位置数据」是否收到坐标。
## 真机安装记录（2026-11-15 21:27）

设备：小米平板 `23043RP34C`，HyperOS `V816`，Android 14 (SDK 34)，adb 序列号 `87159031`。

**安装成功的条件（踩坑结论）**：
1. 开发者选项里的「USB 安装」打开；
2. **平板必须解锁并亮屏** —— 锁屏状态下 `adb install` 一律返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，解锁后同一条命令立刻 `Success`。

中间试过且无效的路径（供以后参考）：`verifier_verify_adb_installs=0`、`install_non_market_apps=1`、`adb_install_need_confirm=0`、`adb push` + `pm install`、`pm install-create/write/commit` 会话法（会话能建、能流式写入 59 MB，commit 仍被拒，说明卡的就是设备端授权而不是 adb 通道）。

**安装结果**：
- `adb install -r -g` → `Success`，`pm list packages` 出现 `com.lutcamera.lutcamerapro`（这是该包第一次装上真机）。
- `am start -n com.lutcamera.lutcamerapro/.MainActivity` 正常启动，`logcat` 无 `FATAL EXCEPTION`，MainActivity 在最上层。
- 首屏渲染正常（见 `docs/screenshots/add-device-real-device.png`）：Wi‑Fi STA / Wi‑Fi AP 直连 / USB PTP / 蓝牙 四张连接方式卡片、相机端设置说明、IP 输入框、两个主按钮全部正常，中文排版无溢出。
- 权限：`INTERNET`、`CHANGE_WIFI_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`BLUETOOTH_SCAN` 等已授予；`ACCESS_FINE_LOCATION` 仍是 `granted=false` —— 因为它是敏感权限，需要在 App 里点「搜索相机 / 扫描蓝牙」时由系统弹窗授予，属于预期行为。

**未完成**：验证过程中 adb 连接掉线（`adb devices` 变空，重启 adb server 也无法恢复，需要重新插线并在平板上重新授权 USB 调试）。所以「插相机跑 PTP」这一段还没做成实测，需要下一次设备在线时继续。
## 真机联调（2026-11-15 21:40–22:10）：连接失败的根因与修复

### 一、真机实测证据：相机到底在哪个 IP

平板（小米 23043RP34C）同时挂着两个 Wi‑Fi：

| 接口 | 地址 | 用途 |
| --- | --- | --- |
| wlan0 | 10.98.15.253/24 | 家里路由器 "Xiaomi" |
| wlan1 | 192.168.125.60/24 | **相机热点（尼康 Z50II AP 模式）** |

相机本体经 ARP 解析为 **192.168.125.174**，`nc` 连 15740 端口阻塞不返回（说明握手成功建立），而 192.168.1.1 完全 ping 不通。

**结论：所谓「能识别到相机但连接不上」，本质是 App 把相机 IP 写死成了 192.168.1.1。** 尼康 AP 直连的网段随机型/固件不同（192.168.1.x、192.168.122.x、192.168.125.x 都出现过），写死任何单一网段都必然失败。

### 二、本轮代码修改（均已编译进 APK）

1. **新增 `network/CameraLanProbe.kt`**：枚举本机每个 IPv4 网卡（wlan0/wlan1/ap0/eth/rndis），对每段 1–254 并发探测 15740 端口（每批 48 个、超时 400ms），命中即判定为相机，返回 IP + 网卡名 + 往返耗时。不依赖 mDNS 广播，也不要求用户知道相机 IP。
2. **`DoukState` 接入探测**：
   - `searchDevices()` 同时跑 mDNS 与端口探测，结果合并进 `discovered`（去重）；
   - 新增 `lanFound / lanScanning / lanScanProgress` 三个界面状态；
   - 新增 `connectSafely()`：连接前先用 `CameraLanProbe.isOpen(ip, 1200)` 验证端口，**不通就不进入 PTP 握手**，而是把「不可达 + 本机网段 + 三条排查项」直接回显到界面；
   - `connect() / connectDirect()` 全部改走 `connectSafely()`。
3. **连接阶段回显**：`NikonViewModel.connectionStage` 是 StateFlow，`DoukRoot` 用 `collectAsState` 同步到 `DoukState.connectionStageMirror`，设备页与添加设备页实时显示「正在连接 / 连接失败：原因」。
4. **修「连接后添加设备按钮点不动」**：原 `AddDeviceScreen` 里的 `LaunchedEffect(state.isConnected) { if (isConnected) onConnected() }` 在已连接状态下每次进入本页都会立即触发，把页面弹回去。改为**连上后留在本页**显示「已连接 · 进入控制」横幅，跳转由用户点按钮触发。
5. **USB 文案纠正**：说明相机「设定菜单 → USB」要选 MTP/PTP（机身屏幕显示「连接至计算机」就是该状态），并注明「优先上传」是照片自动上传开关、不是 USB 控制通道。
6. **新增三项可控参数**（全部真实 PTP 读写，读不到显示「相机未上报」）：
   - `0x5005` 白平衡（自动/晴天/荧光/白炽/闪光/阴天/阴影/色温/预设手动）
   - `0x500E` 曝光模式（M / AUTO / A / S / P）
   - `0x5013` 驱动模式（单张 / 连拍低速 / 连拍高速 / 定时自拍）
   - 控制页参数顺序：曝光组 `iso / shutter / aperture / ev / expMode`；对焦与画质组 `metering / focusMode / afArea / wb / drive / vr / tone / pictureControl`。
7. **诊断日志**：`NikonPtpClient` 对每个属性描述打印 `PROP_DESC OK / NULL / FAIL / MISMATCH`（带响应码与数据类型），取景链路打印 `LIVEVIEW_START 0x9201 -> ...`、`LIVEVIEW_FIRST_FRAME`、`LIVEVIEW_FRAME_FAIL`。

### 三、真机验证结果

| 项目 | 结果 |
| --- | --- |
| 自动探测相机 | 成功。mDNS 列出 `Z50_2_8086819`（192.168.125.174:15740），点设备卡片后建立 PTP/IP 会话 |
| PTP 会话与真机参数 | **连接成功**：控制页显示 `Z50_2 · V1.11`、`ISO 400`、`1/250s`、`F2.8`，全部读自机身 |
| 失败原因回显 | 成功。填 192.168.1.1 点直连，界面显示「192.168.1.1:15740 不可达（手机当前网段 wlan1 192.168.125.x；wlan0 10.98.15.x）…」并给出三条排查项（截图 `docs/screenshots/connect-reason-real-device.png`） |
| 扩展参数 | `0x5010 / 0x500B / 0x500A / 0x5005 / 0x500E / 0x5013` 仍显示「相机未上报」，需读 `PROP_DESC_*` 日志确认响应码 |
| 实时取景 | 未出画面（间隔约 20 分钟时相机热点已掉线，192.168.125.174 ping 不通），待相机重新上线后看 `LIVEVIEW_*` 日志 |

### 四、待办（下一步需要相机保持在线）

1. 相机重新进入「连接至智能设备 → 相机作为热点」，手机连上该热点。
2. 抓 `adb logcat -s NikonPtpClient`，确认 6 个扩展属性的真实响应码，据此决定是改用 `0x1015 GetDevicePropValue` 直读，还是这些属性在 Z50II 上确实不通过 0x1014 返回。
3. 确认取景是卡在 `0x9201`（开始取景被拒）还是 `0x9428/0x9203` 取帧返回 `0xA00B`（相机未进入可传输状态）。
## 修正：什么是「真的连上了」（2026-11-15 22:40）

上一版有两个体验缺陷，本轮已修：

### 1. 「还没连上就显示已连接」

原因在 `NikonPtpClient.startNikonWiWatch()`：原代码只校验了 `0x1002 OpenSession`，之后 `0x1001 GetDeviceInfo` **失败也会继续往下走并置 `_isConnected = true`**；相机只公布少量命令（`restricted`，通常是机身还没确认配对）时同样会置「已连接」。于是界面提前显示连接成功、提前跳控制页，而实际参数一个都读不到。

现在的连接成功判定（三条必须同时成立）：
1. `0x1002 OpenSession` 返回 `0x2001`；
2. `0x1001 GetDeviceInfo` 返回 `0x2001` 且解析出的机身型号非空；
3. 机身公布的命令集不是受限集合（`restricted == false`）。

任一不满足都抛 `IOException`，`isConnected` 保持 false，界面显示具体原因：
- 「相机未返回机身信息（0x1001 响应 0xXXXX），会话未成立」
- 「相机尚未确认与本 App 的配对（机身只公布 N 个命令）。请在相机屏幕点确认…」

界面侧也加了一道：添加设备页顶部的「已连接」横幅现在要求 `isConnected && profile.model != "—"`，单纯 isConnected 变 true 不会再出现横幅。

### 2. STA 模式连不上

STA 模式的前提是**相机自己连到同一个路由器**，和 App 无关。真机实测（2026-11-15 22:30）：

| 网段 | 内容 |
| --- | --- |
| wlan0 10.98.15.x | 家用路由器，只有网关 10.98.15.125，没有相机 |
| wlan1 192.168.125.x | 相机热点网段，相机 192.168.125.174 已掉线 |

也就是说，相机当时**既不在路由器网段，也不在热点网段** —— 这种状态下 STA 怎么填 IP 都不可能连上。

本轮改动：
- STA 模式的 IP 输入框默认清空（不再沿用 AP 的 192.168.1.1），提示改为「路由器分配的地址，留空由搜索自动探测」；
- STA 搜不到时给出针对性说明：`本机网段（wlan1 192.168.125.x；wlan0 10.98.15.x）没有发现 15740 端口。STA 模式下相机要自己连到同一个路由器：相机菜单「连接至智能设备」→ 选中你的路由器 Wi-Fi 并输入密码…`（截图 `docs/screenshots/sta-not-found-real-device.png`）；
- 屏蔽了 `未连接` 这类占位文本，避免它被当成错误提示重复显示。
## 运行日志（2026-11-15 22:20 落地并真机验证）

工程原本只有 `NikonTrace`：滚动 4 个 4 MB 的 trace 文件、含 PTP 报文 hex、可打 zip。它面向协议级排查，内容对人不够友好。本轮补了一层人类可读的运行日志。

### 格式

对齐 GitHub 上常用日志库（KLog / Timber）的默认布局：

```
11-15 22:19:30.252 W/Scan: NOT_FOUND mode=WIFI_AP nets=wlan1 192.168.125.x；wlan0 10.98.15.x
11-15 22:15:07.327 E/Connect: PREFLIGHT_FAIL target=192.168.1.1:15740 localNets=wlan1 192.168.125.x；wlan0 10.98.15.x
```

即 `时间 级别/标签: 事件 key=value`，一行一条，key=value 便于直接 grep 与贴 issue。

### 实现

| 文件 | 作用 |
| --- | --- |
| `log/DoukLog.kt` | 单例日志器：内存 800 行环形缓冲 + 按天落盘 `filesDir/douk-logs/douk-yyyyMMdd.log`（单文件超 2 MB 自动滚动）+ 同步输出 logcat；导出走 MediaStore 写 `Download/Douk/` |
| `ui/douk/DoukLogScreen.kt` | 日志查看页：等宽字体、按级别着色（E 红 / W 橙），支持清空与导出 |
| `ui/douk/DoukSettingsScreen.kt` | 新增「诊断 → 运行日志」入口 |
| `ui/douk/DoukRoot.kt` | 应用启动时 `DoukLog.install(context)`，并挂 `showLog` 页面 |

### 已埋点的事件

| 标签 | 事件 | 说明 |
| --- | --- | --- |
| Connect | `PRECHECK_BEGIN` / `PREFLIGHT_OK` / `PREFLIGHT_FAIL` | 连接前的 TCP 预检，失败时带本机网段 |
| NikonPtp | `CONNECT_BEGIN` / `TCP_CONNECTED` | 开始连接、TCP 建连成功（带 local/remote） |
| NikonPtp | `HANDSHAKE_ACK` / `HANDSHAKE_FAIL` | **相机握手**：失败时记录 InitFail 码 —— 就是机身屏幕显示「连接失败」时能对上的那一条 |
| NikonPtp | `OPEN_SESSION` / `DEVICE_INFO` / `CAPABILITIES` | 会话与机身信息，带响应码、型号、命令数、是否受限 |
| NikonPtp | `CONNECT_FAIL` | 连接整体失败，带所处阶段与异常类 |
| Prop | `DESC_OK` / `DESC_FAIL` / `DESC_NULL` / `DESC_MISMATCH` | 属性描述读取，回答「为什么显示相机未上报」 |
| LiveView | `START` / `FIRST_FRAME` / `FRAME_FAIL` | 取景启动与首帧，带响应码与字节数 |
| Scan | `FOUND` / `NOT_FOUND` | 自动探测结果，带命中的 IP 与本机网段 |
| ViewModel | `STEP` | 原有界面日志同步进统一日志 |

### 真机验证

- 日志页显示 7 条记录，W/E 行按级别着色；
- 导出生成 `/sdcard/Download/Douk/douk-log-20261115-16295.log.txt`（2201 字节），内容含「导出时间 / 设备 Xiaomi 23043RP34C / Android 14 / 本次会话 / 历史文件」四段，样例见 `docs/运行日志示例.log`；
- 界面截图 `docs/screenshots/douk-log-page.png`。

### 相机端「连接失败」怎么用这份日志定位

相机机身显示「连接失败」时，App 侧一定有一条对应记录：

1. 若日志里是 `E/NikonPtp: HANDSHAKE_FAIL initFail=1` → 相机不认这个配对身份，需要在相机「已配对设备」里删掉旧记录后重连；
2. 若没有 `HANDSHAKE_ACK`、只有 `TCP_CONNECTED` 之后超时 → 相机接受了 TCP 但没回握手，通常是相机还停在菜单选择页、或已被其他设备（如 SnapBridge）占用；
3. 若 `CAPABILITIES restricted=true` → 相机只公布了很少命令，说明配对未确认，界面会直接提示「相机尚未确认与本 App 的配对」；
4. 若全是 `PREFLIGHT_FAIL` → 根本没到相机，是网络/IP 问题。
## 连接失败的根因：相机被占用，只给了受限会话（2026-11-15 22:40 定位）

### 真机日志里的硬证据

22:24 那次连接，TCP 与握手全部成功，但相机给的能力集异常小：

```
I/NikonPtp: TCP_CONNECTED local=/192.168.125.60:35578 remote=/192.168.125.174:15740
I/NikonPtp: HANDSHAKE_ACK identity=04b004550000 sessionId=1
I/NikonPtp: OPEN_SESSION code=0x2001
I/NikonPtp: DEVICE_INFO code=0x2001
I/NikonPtp: CAPABILITIES model=Z50_2 firmware=V1.11 commands=9 props=21 restricted=true
E/NikonPtp: CONNECT_FAIL stage=设备可达，正在握手 error=相机只公布了 9 个命令…
```

- `0x1002 OpenSession`、`0x1001 GetDeviceInfo` 都返回 `0x2001`，说明网络、IP、端口、协议格式全部正确；
- 但 `commands=9`（正常 Z50II 应公布上百条）、`props=21`，`restricted=true`。

这不是 App 的问题：相机主动把这次连接降级成「受限会话」，只给最小命令集，曝光、取景一律不可用。相机屏幕上那句「连接失败」是同一次握手的另一端表现。

### 与 GitHub 参考实现的说法一致

`AchooLuv/NikonLink`（Kotlin，同为 Z50II 实测）的 `docs/USAGE.md` 写明：

- 「连不上 / InitFail — 相机被其它设备占用，关掉 SnapBridge 后重开相机 Wi-Fi。」
- 「同一时间只能有一个客户端连相机：请先完全退出 SnapBridge。」

对应到本机情况：用户报告「相机就连接了两台设备」，即相机端认为已有其他客户端在线，于是把新连接降为受限会话。

### 本机已排除的一项

`adb shell pm list packages | Select-String "nikon|snapbridge"` 结果为空 —— 这台小米平板上没有装 SnapBridge，也没有旧版 Wireless Mobile Utility。所以占位者不在本机，只能是相机「已配对设备」里残留的其他手机记录，或上一次未回收的会话。

### 代码侧改动

| 位置 | 改动 |
| --- | --- |
| `NikonPtpClient` | 受限会话的报错改成直接说清原因：`相机只公布了 N 个命令（正常应有上百个），说明它把本连接当成了受限会话…` |
| `NikonPtpClient` | 新增 `IDENTITY` 日志，记录 `hostGuid` —— 重装/清数据会换身份，相机就会多记一条配对 |
| `AddDeviceScreen` | 相机端设置提示改为 Nikon 官方路径：`MENU → 网络菜单 → 连接至智能设备 → Wi-Fi 连接 → 建立连接`，并强调连相机热点时要选「保持连接」、关掉「WLAN+ / 智能网络切换」 |
| `AddDeviceScreen` | 新增橙色提示：相机一次只允许一台设备，要在「已配对设备」里删掉旧记录并完全退出 SnapBridge |
| `DoukState` | 蓝牙入口提示补上：相机蓝牙同样会被 SnapBridge 占用，需先删除已配对设备 |

### 用户侧处置步骤（照这个顺序做）

1. 相机：`连接至智能设备 → 已配对设备`，把里面的记录**全部删除**（会看到以前连过的手机名字）；
2. 相机：重新走 `Wi-Fi 连接 → 建立连接`，让相机重新开热点；
3. 手机：Wi‑Fi 连 `NIKON_…`，提示「无法访问互联网」时选**保持连接**；关掉「WLAN+ / 智能网络切换」；
4. 打开 Douk → 搜索 → 连接；
5. 若仍失败，取 `Download/Douk/douk-log-*.log`（失败时会自动生成）回传 —— 日志里的 `commands=N` 能直接判断是否仍被占用：N 上百即正常，个位数十几个就还是受限会话。

### 判定口诀

| 日志现象 | 含义 |
| --- | --- |
| `commands=9 props=21 restricted=true` | 相机被占用，只给受限会话 |
| `commands>100` 且无 `CONNECT_FAIL` | 正常会话，可读写参数与取景 |
| 只有 `TCP_CONNECTED` 无 `HANDSHAKE_ACK` | 相机接受了 TCP 但没回握手（相机停菜单页或已满） |
| 只有 `PREFLIGHT_FAIL` | 网络/IP 层，未到相机 |
## 更正：不是「被占用」，是尼康的「待配对」状态（2026-11-15 22:56）

### 上一节结论错了

22:45 的会话日志显示，相机每次都**成功**完成握手与会话建立：

```
I/NikonPtp: TCP_CONNECTED local=/192.168.125.60:39588 remote=/192.168.125.174:15740
I/NikonPtp: HANDSHAKE_ACK identity=04b004550000 sessionId=1
I/NikonPtp: OPEN_SESSION code=0x2001
I/NikonPtp: DEVICE_INFO code=0x2001
I/NikonPtp: CAPABILITIES model=Z50_2 firmware=V1.11 commands=9 props=21 restricted=true
```

`0x2001` 三次都是成功响应。如果真是被 SnapBridge 占用，相机会拒绝建立会话，而不是建好会话后只给一小部分命令。

### 真正的含义：`0x952b` + `0x935a`

相机给的那 9 个命令里包含 `0x952b`（请求配对）和 `0x935a`（提交配对确认），但不含 `0x9201` / `0x9203`（正常拍摄控制）。

这正是尼康 Z 系列**首次连接必须先配对**的最小权限集：客户端调 `0x952b`，相机屏幕显示 4 位验证码，核对一致后调 `0x935a` 提交，机身上按确定完成向导，再重连就能拿到完整能力集。

App 原来的做法是「检测到 restricted 就抛 IOException 关掉会话」，于是：
- 会话被自己掐断 → 相机屏幕显示「连接失败」；
- `isConnected` 被置 false → 配对按钮永远不可用。

两者互为死锁，配对流程一次都走不到。

### 这一版改了什么

| 位置 | 改动 |
| --- | --- |
| `NikonPtpClient` | `restricted` 不再抛异常：置 `pairingRequired=true`，**保留会话**，阶段文案改为「相机待配对：请点开始配对」 |
| `NikonPtpClient` | 新增 `requestPairingNow()` / `submitPairingNow()`，直接用当前会话发 `0x952b` / `0x935a`，不必再传会话版本 |
| `NikonPtpClient` | 连接开始与断开时清空待配对标记 |
| `DoukState` | 新增 `pairingRequired` / `pairingCode` / `pairingBusy` / `pairingMessage` 与 `startPairing()` / `submitPairing()` |
| `AddDeviceScreen` | 待配对时显示「相机需要配对」卡片，按钮在「开始配对」与「确认配对」之间切换 |
| `DoukLog` | 按用户要求**去掉自动导出**，只在 `filesDir/douk-logs/` 逐行落盘；导出改为设置页手动点 |

### 配对操作顺序

1. App 里点「直接连接该 IP」→ 出现「相机需要配对」卡片；
2. 点「开始配对」→ 相机屏幕弹出 4 位数字，App 上同步显示；
3. 两边数字一致 → 点「确认配对」→ 相机上按「确定」；
4. 再点一次「直接连接该 IP」→ 这次应拿到上百个命令，状态变成「已连接」。

### 当前环境状态（22:56 实测）

```
ping 192.168.125.174  →  2 packets transmitted, 0 received, 100% packet loss
cmd wifi status       →  Wifi is connected to "Xiaomi"（家里路由器）
wlan1 192.168.125.60  →  相机热点断开后残留的旧 IP
```

相机的 Wi-Fi 已经断开（热点消失），所以 App 连预检都过不去。需要先在相机上重开「连接至智能设备 → Wi-Fi 连接 → 建立连接」，手机重新连上 `NIKON_…` 热点后，配对流程才有对象可测。
## 品牌与交互调整（2026-11-15 23:04）

### 命名

| 项 | 值 | 位置 |
| --- | --- | --- |
| 中文名 | 连镜 | `res/values/strings.xml` 的 `app_name` |
| 英文名 | LensLink | `res/values-en/strings.xml` 的 `app_name` |
| 设置页「关于」 | 连镜 / 3.26-nikon-ui-refinement | `DoukSettingsScreen` |

桌面显示随系统语言切换：中文环境显示「连镜」，英文环境显示「LensLink」。

### 图标

源图 `C:\Users\LUO\Desktop\download1.png`（1024×1024，满幅橙→粉紫渐变 + 白色相机 + 黄环棕底双链）。

生成脚本 `_tmp\make_icon_download1.py`，产出：

| 资源 | 规格 | 说明 |
| --- | --- | --- |
| `drawable/douk_icon_bg.png` | 432×432 | 原图渐变直接缩放，作为自适应图标背景 |
| `drawable/douk_icon_fg.png` | 432×432 | 白色相机主体，占画布 66%，其余透明 |
| `mipmap-*/ic_launcher.webp` | 48/72/96/144/192 | 满幅原图，交给启动器套自己的形状 |
| `mipmap-*/ic_launcher_round.webp` | 同上 | 圆形遮罩版 |

前景抠图用「白色 mask + 四角灌水标记外部」，只保留机身与机身内部的圆环；渐变区域一律置透明，因此任何遮罩下都不会露出方形色块。

旧版图标曾自带圆角，和系统圆角叠成两层边，已改为满幅。

### 添加设备页：搜索为主，IP 直连为备用

| 顺序 | 内容 |
| --- | --- |
| 1 | 选择连接方式（Wi‑Fi STA / Wi‑Fi AP 直连 / USB PTP / 蓝牙） |
| 2 | 相机端设置提示 |
| 3 | **「搜索相机设备」主按钮**（橙色实心，当前模式的搜索或枚举动作） |
| 4 | 搜索进度（当前网段 15740 端口探测）与「发现的设备」列表 |
| 5 | 模式专属卡片（USB PTP 通道 / 蓝牙 GPS 推送） |
| 6 | 连接阶段与失败原因 |
| 7 | 待配对卡片（相机只给最小能力集时出现） |
| 8 | 「搜索不到？手动填相机 IP（备用）」折叠项，展开后才是输入框与「用这个 IP 连接」次按钮 |

手动 IP 默认收起，按钮改用浅底样式，不再和主搜索按钮抢视觉权重。
## 配对成功但界面显示未连接：原因与修复（2026-11-15 23:10 实测日志）

### 日志原文（session-20261115-230929.log）

```
23:10:12.447 I/NikonPtp: CAPABILITIES model=Z50_2 firmware=V1.11 commands=9 props=21 restricted=true
23:10:12.448 I/NikonPtp: PAIRING_REQUIRED model=Z50_2 commands=9 props=21
23:10:17.424 I/NikonPtp: PAIR_CODE code=2687
23:10:33.600 I/NikonPtp: PAIR_SUBMIT code=0x2001
23:10:45.916 I/ViewModel: STEP msg=🔴 尼康 (Nikon) 相机已断开: PTP/IP connection closed mid-packet
23:10:45.918 E/Pair: REQUEST_FAIL error=PTP/IP connection closed mid-packet
```

### 读这段日志

1. 会话正常建立，相机给了最小能力集，App 进入「待配对」—— 判定正确；
2. 点「开始配对」，相机回传验证码 `2687` —— 配对请求成功；
3. 点「确认配对」，相机回 `0x2001` —— **配对已被相机接受**；
4. 随后相机主动关掉了这条 PTP 会话（`connection closed mid-packet`），这是配对完成后的正常行为，下一次连接才会给完整权限；
5. 用户在 23:10:45 又点了一次「开始配对」，此时会话已关闭，所以报错。

界面停在“未连接”，是因为配对提交后 App 只是把文案改了，没有重新建会话。

### 修复

`DoukState.submitPairing(context)` 现在在 `0x2001` 之后自动接着做：

1. 清掉 `pairingRequired`；
2. 断开旧会话（`vm.disconnect()`）；
3. 等 1.2 秒让相机回收旧会话；
4. 用 `lastTargetIp`（最近一次连接的相机 IP）自动重连，日志记 `I/Pair: RECONNECT`。

这样配对完不用手动再点，界面会自己走回「已连接」，日志里应能看到重连后 `commands` 从 9 涨到上百。

### 当前环境状态（23:15）

```
ping 192.168.125.174  →  100% packet loss
ip neigh              →  192.168.125.174 dev wlan1 FAILED
```

相机的 Wi-Fi 又掉了。配对后的完整能力集能否拿到，要等相机热点稳定在线才能验证。

### 另一个待办

设备页中间「点击此处更换相机图片」目前只是占位文案，尚未接相册选择器（设置页也写着“当前版本预留了字段”）。
## 重要更正：不是「待配对」，是没切应用模式（0x9435）

### 证据链

1. 日志里会话能建起来，但 `CAPABILITIES commands=9 props=21 restricted=true`，且这 9 条里含 `0x952b` / `0x935a`。
2. 点「开始配对」能拿到 4 位码（23:10 是 `2687`，23:16 是 `0836`），提交也返回 `0x2001`，
   但**相机机身屏幕始终不显示验证码** —— 用户实际观察。
3. 查协议资料后确认：`0x952B GetPairingInfo` / `0x935A ConfirmPairing` 是**尼康 ZR 机型专用**（网络模式），
   Z50II 这类二代机根本不走这个配对向导。所以相机屏幕不会弹码，App 拿到的码只是相机随手回的字节。
4. 真正的关键指令是 `0x9435 ChangeApplicationMode`（参数 `[1]`）。官方 SDK 与
   `LilDandong/NikonRemotePro` 的 `NIKON_PTP_SDK_REFERENCE.md` 把它写成铁律：
   「若跳过 `0x9435 ApplicationMode`，相机不开放宿主取景控制」。
5. 标准握手顺序应为：
   `OpenSession(0x1002)` → `GetDeviceInfo(0x1001)` → **`ChangeApplicationMode(0x9435, [1])`**
   → `StartLiveView(0x9201)` → 轮询 `DeviceReady(0x90C8)`。

### 代码改动

`NikonPtpClient.connect()` 在 `CAPABILITIES` 判定出 restricted 之后，先做一次应用模式切换：

```kotlin
if (c.restricted) {
    val unlock = runCatching { operation(0x9435, intArrayOf(1), expected = c) }.getOrNull()
    // 日志: I/NikonPtp: APP_MODE op=0x9435 code=0x????
    if (解锁返回 0x2001) {
        operation(0x1001)  // 重新协商能力集
        // 日志: I/NikonPtp: CAPABILITIES_AFTER_MODE commands=? props=? restricted=?
    }
}
```

- 受限白名单从 `{0x1001,0x1002,0x1003,0x952b,0x935a}` 扩为再放行 `0x9435`；
- 解锁后如果命令集变大，就按正常会话走（置 `isConnected`、启动取景与事件轮询），不再进配对分支；
- `0x952b` 配对流程保留为兜底（ZR 机或真需配对的场景）。

### 待验证

需要相机 Wi‑Fi 稳定在线才能看到 `APP_MODE code=0x????` 和 `CAPABILITIES_AFTER_MODE commands=?`。
观测点：若 `commands` 从 9 涨到几十/上百且 `restricted=false`，说明这条就是解题关键。

### 实测环境问题（23:19–23:21）

```
ping 192.168.125.174 → 连续 8 次 100% packet loss
```

相机热点反复掉线，无法完成一次完整连接。相机侧建议：停在「连接至智能设备 → Wi‑Fi 连接」界面不要退出，
关闭机身省电/自动传送结束即断开的设置，或改用相机自建热点（AP）模式接入。
## 设置页四组落地 + 关于独立成页

### 结构

设置页（`DoukSettingsScreen.kt`）现在只有四组，所有值都读写 `DoukPrefs`（SharedPreferences），
重开 App 依然在，不再是打开就重置的内存开关：

| 分组 | 条目 |
| --- | --- |
| 设备 | 自动连接已连接过的设备（开关）、已连接过的设备（列表可连接/删除）、默认连接方式 |
| 下载 | 自动下载相机照片（开关）+ 照片大小 / 文件格式 / 保存位置；只下载相机里锁定的文件（开关）+ 锁定文件大小 / 格式 / 保存位置 |
| 通用 | 储存、权限说明、语言、资源·LUT 与滤镜、GPS 数据导入、外观设置、拍摄后即时预览、设备页相机照片 |
| 关于 | 一个入口卡片（图标 + 连镜 + 版本），点开进独立界面 |

控制面板只保留「参数排列顺序」一项。**运行日志入口已按用户要求从设置页移除**，日志仍在
`filesDir/douk-logs` 持续落盘，只是不在界面里显示。

### 关于：单独一屏

`AboutPanel` 是独立界面（不是弹窗）：顶部返回箭头 + 「关于」标题，下面是品牌卡（76dp 图标、
名字、版本、一句话描述），再下面是分类卡片：

检查更新 / 更新日志 / 反馈与建议 / 使用条款 / 捐赠作者。

（「开源归属」这一项按用户要求已从界面移除；工程内的许可证文件仍在，仅不展示在 UI。）

- 返回箭头与系统返回键都退回到设置页（`BackHandler`）；
- 各分类点开是说明弹窗，反馈会拉起邮件客户端并预填版本、机型、系统与日志路径。

### 已经真正接线的功能

1. **自动连接**：`DoukRoot` 启动时读 `autoConnectKnown`，有记录就 `connectDirect` 最近一台；
2. **设备记录**：连上就把 IP 与型号写进 `rememberDevice`，列表里可再次连接或删除；
3. **主题色 / 深色模式**：选择后立刻 `applyAccent` / `applyDark`，启动时按 `DoukPrefs` 恢复；
4. **设备页相机照片**：设置页或设备页点图片都会打开相册选择器，图存 `filesDir/camera-photo.jpg`，
   设备页与设置页共用；`state.photoVersion` 负责让设备页刷新生效；
5. **LUT 与滤镜导入**：`GetContent` 选文件后拷进 `filesDir/lut`，列表可看大小与删除；
6. **储存**：图片、LUT、日志、诊断记录分别统计，可导出 `douk-settings-*.json`；
7. **默认连接方式改为 Wi-Fi STA**（原来默认 AP 直连，与「搜索为主」的用法相反）。

### 踩到的坑

`painterResource(id = R.mipmap.ic_launcher_round)` 直接崩溃：
`IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported`。
原因是我们图标是 `mipmap-anydpi` 的 adaptive-icon XML，Compose 的 painter 读不了。
解法：另导一张位图 `res/drawable/douk_app_icon.png`（192×192，由 `_tmp/icon_new_legacy.png` 缩出）给界面用。
## 设备页去文字化 + 下拉刷新（第二轮 UI 细化）

### 设备页
- 未连接时照片卡上不再叠任何状态文字（原来的「未连接相机 / 正在连接相机…」整行删除），
  只显示照片本身；没设照片时显示灰底相机图标。
- 连接失败/进行中的原因从图片上移到了图片**下方**，图面保持干净。
- 照片只做展示：设备页不再接收点击换图，换图统一走「设置 → 通用 → 设备页相机照片」
  （两处读同一个 `filesDir/camera-photo.jpg`，设置页换完回来即生效）。
- 「支持的连接方式」恢复显示蓝牙，并附一行说明：蓝牙不传画面，只推 GPS，控机看图请用 Wi-Fi / USB。

### 下拉刷新（重搜索）
设备页顶部下拉一把即重新搜索相机，指示条随下拉距离展开：
0 → 「下拉搜索相机」→ 到阈值变「松开搜索相机」→ 松手后转圈显示「正在搜索相机…」，搜索结束自动收起。

实现踩坑：**Compose 的 `nestedScroll` 收不到下拉位移**。Android 上 `scrollable` 会把边界 overscroll
交给 OverScrollEffect 消费，`onPostScroll` 的 available 恒为 0，所以第一版完全没有反应（真机验证过）。
改为在 `PointerEventPass.Initial` 阶段自己抢手势：只有「内容已滚到顶部（`scrollState.value == 0`）
且手指向下移动超过 30px」才判定为下拉，判定后调用 `change.consume()` 阻止列表滚动，
松手时位移超过 72dp 阈值才触发 `state.searchDevices(context)`。
真机日志确认触发链路：`W/Scan: NOT_FOUND mode=WIFI_STA nets=wlan1 ...；wlan0 ...`。

### 设置页
- 「默认连接方式」这一项已替换为 **「设备页显示『添加设备』按钮」** 开关
  （`DoukPrefs.showAddDeviceButton`，默认开；关掉后设备页只留顶部 + 号入口）。
  开关与 `DoukState.showAddDeviceButton` 同步，切回设备页立刻生效（已真机验证）。

### 关于页
- 删掉品牌卡下的「连接尼康相机的安卓应用」副标题；
- 删掉「开源归属」分类（协议层信息仍写在文档与使用条款第七节里）；
- 「使用条款」扩写成十节：关于本应用 / 相机连接与设备要求 / 数据与隐私 / 照片与文件 /
  权限使用 / 使用风险与免责 / 知识产权与开源 / 服务变更与终止 / 适用法律 / 条款更新与联系方式。

### 控制页
- 画面隐藏时那条「画面已隐藏，点击显示」的文字提示去掉，只留一个眼睛图标，点一下恢复画面。

### 注意
添加入口页**不再**有底部搜索按钮（上一版加过又按反馈撤掉）：搜索只从设备页下拉、顶部刷新、添加设备页主按钮发起。
---

## UI 设计系统（ui-design-system 落地）

### 来源

2026-11-16 安装 `ui-design-system` skill（用户上传 `ui-new-1.0.0.zip`），
装到 `C:\Users\LUO\.agents\skills\ui-design-system`，含 SKILL.md 与
references/color-systems.md、component-patterns.md、layout-templates.md。
（SKILL.md 引用的 references/animation-library.md 包内缺失，动效曲线按 Material 标准
自己定义。）

该 skill 的产出规范原本面向 HTML/CSS（Material 3 语义色 + Apple HIG 8pt 网格 +
Pinterest 留白）。移植到 Compose 的做法是：把规范抽成 token 层，再逐页替换魔法数字。

### token 层（`ui/douk/DoukTheme.kt`）

**DoukColors**（语义色，全部按深浅色成对取值）
- accent / accentSoft(10%) / accentContainer(16%) / accentOutline(34%)
- background / surface / surfaceRaised / surfaceMuted（surface container 分级）
- textPrimary / textSecondary / textTertiary（三级文本）
- divider / outline
- success + successSoft / warning + warningSoft / danger + dangerSoft
- previewBackground（取景黑底）
- applyAccent(name) / applyDark(b)

**DoukSpace**（8pt 网格）：xxs4 / xs8 / sm12 / md16 / lg20 / xl24 / xxl32 / xxxl40 / page20

**DoukShapes**：icon 12dp / cardSmall 14dp / card 20dp / pill / sheet(顶部 28dp)

**DoukType**（字重 + 行高 + 字距成套，颜色交给调用处，不进 TextStyle）
- display 30/36 Bold -0.6、h1 22/28 Bold -0.4、h2 17/24 SemiBold -0.2
- title 15/22 SemiBold、body 14/21、bodySmall 13/19、caption 12/17
- label 11/15 Medium +0.06、section 12/17 Medium +0.06（分组标题全站统一用它）
- value 15/20 SemiBold -0.2、tab 11/14 Medium +0.02

**DoukElevation**：none0 / card3 / raised10 / overlay24；
扩展 `Modifier.doukShadow(elevation, shape)`，ambient 0x14000000 + spot 0x1A000000。

**DoukMotion**：short150 / medium250 / long380；
standard CubicBezier(0.2,0,0,1)、decelerate(0,0,0.2,1)、spring(0.34,1.4,0.64,1)。

### 页面重构要点

**设备页 `DeviceScreen.kt`**（整页重写，状态机与手势逻辑未动）
- 顶部标题 h1，右侧三个 44dp 触达圈图标（+ / 刷新 / 手册）
- 照片区 `PhotoHero`：圆角 20 + doukShadow(card)；未连接时不叠任何文字，
  无照片时是「主色光晕圆 + 相机图标」的占位；已连接时叠底部暗渐变、左上「在线」胶囊、左下机型名
- 连接失败/进行中的原因仍在照片下方（caption + warning 圆点），图面保持干净
- 已连接信息格 `InfoTile`：26dp 主色胶囊图标 + caption 标签 + value 主值，2×2
- 主按钮 54dp 主色填充（进入控制面板）；断开连接用 surfaceMuted 底中性按钮
- 连接方式行：36dp 图标胶囊（Wi-Fi / USB / 蓝牙）+ 标题 + 说明 + chevron；蓝牙行第三行仍是 GPS 说明
- 使用手册弹窗改 sheet 圆角、h2 标题

**控制页 `ControlScreen.kt`**
- 顶部 h1 + 拍摄/视频分段控件（选中项浮成白胶囊）
- 未连接提示条换成 warningSoft 底 + warning 文字（语义更准，内容不改）
- 预览卡：圆角 20 + raised 阴影；右上 34dp 半透明圆形全屏按钮，右下「隐藏」胶囊；
  无画面时是「摄像机图标 + 等待第一帧取景画面 + 机型·模式」三层
- 参数组：左 label 用 textSecondary、右值用 textPrimary value 字级（原来值用主色，满屏橙字，
  改成中性色后主色只留给操作），行间 1dp 分割线，缩进 16
- 参数弹窗：sheet 圆角，选中项 accentSoft 底 + 主色字 + 右侧对勾
- 快门区：计数 pill / 74dp 白环主色快门 / AF 或 REC 圆按钮

**设置页 `DoukSettingsScreen.kt`**
- 标题 h1；分组标题统一 `DoukType.section`（12sp + 字距 .06），行距放宽
- SettingRow / SettingSwitchRow 统一：标题 body Medium、副标题 caption、右侧值 textTertiary
- 关于页独立成屏：返回按钮 44dp、标题 h1、品牌卡圆角 20 + h2 品牌名

**图库 `GalleryScreen.kt`**
- 顶部标题 h1，来源切换 chip 用 caption；图标按钮 44dp
- 网格保持小米相册风格（3 列、12 间距、缩略图近方角），只统一字级与顶部间距

**添加设备页 `AddDeviceScreen.kt`**
- 标题 h1、返回 44dp、分组标题 section
- 已连接横幅换 successSoft + 绿图标，「进入控制」按钮改 36dp 胶囊
- 选中卡片的橙描边来自原逻辑，未改

**底部导航 `DoukRoot.kt`**
- 选中项改成「图标胶囊托起」（46×27 主色浅底 pill），去掉原来那根 16dp 小横线；
  图标 21dp，标签用 tab 字级

### 踩过的坑

1. `awaEachGesture` 之外，**IconButton 的命中区不可靠**：给 IconButton 加 `Modifier.size(44.dp)`
   后 uiautomator 报出来的 content-desc 节点只有 50px（20dp），点偏了就没反应。
   改成 `Box(Modifier.size(44.dp).clip(CircleShape).clickable(...))` 后命中区稳定 110px（已验证）.
2. 分组标题从 11sp 提到 12sp：11sp 在 400dpi 屏上偏细，跟正文视觉差得太小。
3. 参数值不要用主色：一屏十几个橙字会把「主色 = 可操作」这个语义冲掉，值改中性色后
   主色只剩快门、按钮、选中态。

### 还没做的

- 设置页各弹窗内部的说明文字仍是旧字号（12sp 字面值），影响小，留着；
- 图库缩略图圆角仍是 4dp（小米相册那种近方角），属于刻意的风格选择，不是漏改；
- `DoukMotion` 只定义了曲线与时长，页面里还没有真正的转场动画，需要时再挂
  `animate*AsState(easing = DoukMotion.standard)`。

### 编译与验证记录

- build52 ~ build57：主题重写 → 设备页 → 控制页 → 设置/关于 → 图库/底部导航 → 触达区修复，
  逐轮 `assembleDebug` 通过；
- 真机（87159031）装机后逐页截图确认：设备页、控制页、图库、设置、添加设备页均已生效，
  顶部 + 号扩宽后点边缘也能打开添加设备页。
---

## Android 官方 Skill 第二轮：全面屏、返回键、主线程与权限

这一轮不再改视觉，按 `C:\Users\LUO\.agents\skills` 下九个 Google Android Skill 逐条对照，
只落地「本工程确实存在缺陷、且改动可控」的部分。工程仍然是 compileSdk/targetSdk 34。

### 一、edge-to-edge（全面屏）

| 改动 | 文件 |
|---|---|
| `enableEdgeToEdge()`，SDK 29+ 关掉 `isNavigationBarContrastEnforced` | `MainActivity.kt` |
| `SideEffect` 里按 app 自己的深色状态设 `isAppearanceLightStatusBars/NavigationBars` | `DoukTheme.kt` |
| `windowSoftInputMode="adjustResize"` | `AndroidManifest.xml` |
| 添加设备页、运行日志页这两个「主 Scaffold 之外的全屏页」补 `statusBarsPadding() + navigationBarsPadding()` | `DoukRoot.kt` |

要点：`enableEdgeToEdge()` 走 `WindowCompat` 版本，所以系统栏图标深浅必须自己设；判断依据是
`DoukColors.darkState`（app 内有独立深色开关，不能跟系统主题走）。主 tab 区由 `Scaffold` 的
`innerPadding` 负责，底部栏本身已带 `navigationBarsPadding()`，不要在外层再包一层 padding。

**没有升 targetSdk 35**：edge-to-edge skill 的前置条件是 targetSdk ≥ 35，但升上去会连带
Android 15 的强制全面屏、前台服务类型等行为变更，回归面远大于本轮收益，因此手动实现同一效果。

### 二、返回键（navigation-event 的替代方案）

`androidx.navigationevent` 要求 compileSdk ≥ 36，本工程不引入；改用 `androidx.activity.compose.BackHandler`
补齐三条真实缺失的返回路径：

- 运行日志页：返回键回「关于/设置」，不再直接退出 Activity（`DoukLogScreen.kt`）
- 添加设备页：返回键回设备页（`AddDeviceScreen.kt`）
- 控制页全屏预览：返回键先退出全屏（`ControlScreen.kt`）
- 日志页覆盖层的返回键在 `DoukRoot.kt` 也注册了一次，保证正在加载日志时同样能退出

### 三、ANR 审计（anr-audit-and-fix 静态审计）

按 risk_analysis_guide 的「证明执行线程 + 证明阻塞」两步筛，锁定并修掉两处主线程重活：

1. `DeviceScreen.kt` 设备页头图：原来在 Composable 里 `BitmapFactory.decodeFile` 全尺寸解码。
   改为 `produceState` + `withContext(Dispatchers.IO)`，并新增 `decodeHeroPhoto()` 按目标宽度
   （1400px）计算 `inSampleSize` 采样，4000×3000 的照片不再一次性解出 48MB 位图。
2. `DoukSettingsScreen.kt` 通用分组：`importedResources()` / `dirSize()` 在 Composable 首帧同步扫目录。
   改为初始 0 + `LaunchedEffect` 里 `Dispatchers.IO` 统计后回主线程赋值。

其余候选（日志写文件、缩略图解码、MediaStore 查询）经核对都在 IO 线程，未列入 finding。

### 四、Compose 性能（jetpack-compose-performance）

- 新增依赖 `androidx.lifecycle:lifecycle-runtime-compose:2.8.2`，把 `DoukRoot`、`ControlScreen`、
  `AddDeviceScreen` 里的 `collectAsState()` 批量换成 `collectAsStateWithLifecycle()`：
  页面不在前台时不再收 StateFlow，减少后台无用重组。
- 图库三处 `LazyVerticalGrid` 的 `items` 补 `contentType = { "thumb" }`：滚动时同类型 item
  可复用 composition slot。注意 `LazyGridScope.items` 的 `contentType` 只接收 item 一个参数，
  写成 `{ _, _ -> ... }` 会编译失败（本轮踩过）。
- 相册/应用内缩略图原本就在 `withContext(Dispatchers.IO)` 里解码，保持不变。

### 五、权限（android-permissions-security）

- `POST_NOTIFICATIONS`：**不在启动时弹**，改到「第一次真的连上相机」时请求一次
  （`DoukRoot.kt` 里 `LaunchedEffect(state.isConnected)`），拒绝也不影响连接与传输。
- 图库「手机」来源：补完整的相册权限流程（`GalleryScreen.kt`）——
  Android 13+ 用 `READ_MEDIA_IMAGES`，12 及以下用 `READ_EXTERNAL_STORAGE`；
  未授权时显示说明页 +「授予权限」按钮，已被永久拒绝时给「去设置开启」直达应用详情。
  说明文案与 Manifest 实际权限一致，没有申请用不到的权限。
- 设置页「权限说明」补齐两条：USB 设备访问、手机相册仅读缩略图。

### 六、intent 安全（android-intent-security）

审计结论：Manifest 中只有 `MainActivity` 是 `exported="true"`（launcher + USB 插入静态响应，必须如此），
两个前台服务均为 `exported="false"`，无 provider / receiver。代码里没有
`intent.getStringExtra("calling_package")` 这类可伪造的调用方校验；USB / 蓝牙的
`getParcelableExtra` 读的都是系统广播里的 `UsbDevice` / `BluetoothDevice`，来源为系统进程。
本轮无需改动，记录为「未发现」。

### 七、测试（testing-setup）

工程原本已有 JUnit4 单测（`app/src/test/...`：`PtpIpWireTest`、`NikonTraceTest`、`CubeParserTest`、
`PhotoInboxTest`、`PhotoExportPolicyTest` 等），本轮只需回归，不新增框架：

```
:app:testDebugUnitTest   # BUILD SUCCESSFUL in 16s
```

`PtpIpWireTest` 覆盖 PTP/IP 帧头长度校验、事务ID错配、分片数据重组、`0x200f` 空数据错误、
`numericProperty` 的描述符/枚举解析、`deviceInfo` 能力集，以及本地 ServerSocket 上的连续两笔事务。
改协议层前先跑它。

### 八、未做 / 明确不做

- `agp-9-upgrade`：升级 AGP 9 需要 Gradle 9 + Kotlin 2.x + compileSdk 36，整套工具链要动，
  与当前「先保证能连上相机」的目标冲突，本轮不升。现状 Gradle 8.11.1 + AGP 8.5.1 可用。
- `play-policy-insights`：本项目不开源上架 Play，只保证权限说明与实际权限一致（见第五节）。
- 0x9435 `ChangeApplicationMode` 在真机上仍未被观测到成功（`APP_MODE` / `CAPABILITIES_AFTER_MODE`
  两行日志始终没抓到，相机热点在连接阶段掉线），属于相机侧网络问题，与本轮 UI/稳定性改动无关。

### 九、验证记录

- build58：edge-to-edge + 三处 BackHandler + 头图异步采样 + lifecycle 收集 —— `assembleDebug` 通过
- build59：通知权限时机改到「首次连上」—— 通过
- build60：图库 contentType 首次写法参数个数不对，编译失败（记录在案）
- build61：修正后通过
- `:app:testDebugUnitTest`：16s 通过
- 真机（87159031）截图验收（解锁后）：设备页、控制页、图库（相机未连空态 + 手机相册 63 张）、
  设置页、添加设备页、关于页全部正常；全面屏下状态栏与手势条区域为页面底色，标题未被遮挡，
  底部 tab 栏位于手势条上方
- 返回键实测：进添加设备页后按系统返回，回到设备页，`topResumedActivity` 仍是
  `com.lutcamera.lutcamerapro/.MainActivity`，确认 `BackHandler` 生效且没有退出应用
- build62：关于页文案修正（「使用条款」副标题与条款第七条去掉开源归属字样）—— 通过并已装机复验

## 十、下载二级页与图库时间轴（第二轮小改）

### 10.1 下载：从一长串开关改成两个入口

设置页「下载」分组原来把 8 行平铺（开关 + 大小 + 格式 + 位置 ×2），实际用起来像一张表。
现在只留两行入口，点进去才是各自的规则页：

| 入口 | 副标题右侧 | 二级页标题 |
| --- | --- | --- |
| 自动下载 | 已开启 / 已关闭 | 自动下载 |
| 下载保护文件 | 已开启 / 已关闭 | 下载保护文件 |

- 二级页结构：顶部返回箭头 + 标题 → 卡片（开关）→ 「回传规则」卡片（5 行）→ 一句状态说明。
- 返回：二级页注册 `BackHandler`，返回键退回设置主页，不会退出应用。
- 两个入口各自 `remember` 自己的本地状态，互不影响；切换来源不会互相覆盖。

保存字段（`DoukPrefs`）：

| 场景 | 图片大小 | 图片格式 | 视频大小 | 视频格式 | 保存位置 |
| --- | --- | --- | --- | --- | --- |
| 自动下载 | `downloadSize`（原图 / 仅缩略图） | `downloadFormat`（JPG / JPG+RAW / 全部格式） | `downloadVideoSize`（原视频 / 仅缩略图） | `downloadVideoFormat`（MOV / MP4 / 全部格式） | `downloadDir`（应用相册 / 系统相册 / Download） |
| 下载保护文件 | `lockedSize` | `lockedFormat` | `lockedVideoSize` | `lockedVideoFormat` | `lockedDir` |

视频字段是这一轮新增的（`download_video_size` / `download_video_format` /
`locked_video_size` / `locked_video_format`），默认「原视频」「MOV」——尼康机身录的就是 MOV。

### 10.2 图库：照小米相册 5.x 的交互重做

参考项目：`IacobIonut01/ReFra`（3k 星的 Compose 相册）、`googol-apps/Gallery`、
`MohamadOday/iris-gallery`（Material You 3）、`yuriy-budiyev/sketches`（含视频与缩放）。
落地的是小米相册最有辨识度的四件事：

1. **时间轴分组**：按日期切段，日期头左对齐、字号 13sp 加粗，文案「今天 / 昨天 / M月D日 /
   YYYY年M月」；相机内（PTP）单独用「机身照片 N 张（USB PTP / Wi‑Fi PTP）」代替日期，
   因为 `GetObjectHandles` 只给句柄顺序、没有可靠拍摄时间，**不伪造日期**。
2. **4 列贴边网格**：`GridCells.Fixed(4)`、间距 2dp、内容留白 2dp、圆角 2dp，
   比原来的 3 列 + 12dp 边距更接近系统相册的密度。
3. **长按多选**：`combinedClickable` 的 `onLongClick` 进入选择模式；顶部标题区换成
   「取消 / 已选择 N 项 / 全选（选中全部后变取消全选）」；点缩略图在多选模式下切换选中。
   选中态是浅橙蒙层 + 右上角圆形对勾，未选中时显示半透明空心圆。
4. **底部操作条**：选择模式下显示一句来源说明和批量动作。
   应用内（`Pictures/Douk`、`DCIM/Douk`）可以批量删除，删除前有确认弹窗，写明
   「删的是应用下载目录里的副本，相机与手机相册原图不受影响」；
   相机内与手机内不提供删除入口，文案分别提示去相机上删、去系统相册删。

顺带修掉一个真实缺陷：缩略图缓存原来用 `Map<Int, Bitmap>`，而相机 handle 与相册
media id 可能撞号，切来源后会把别的来源的图串过来。现在缓存键改成 `来源:键`
（`stableKey`，如 `usb:1234`、`phone:5678`），`GalleryItem` 新增 `origin` 字段承担这件事。

数据侧新增：`GalleryItem` 多了 `origin`、`dateMillis`、`isVideo` 三个字段。
- 应用内取 `File.lastModified()`；
- 手机内取 `MediaStore.DATE_ADDED`（秒转毫秒）；
- 相机内保持 `null`。

### 10.3 边界（这一轮没做）

- 批量「保存到手机」：相机内多选只支持 `state.saveUsbPhoto` 的单张回调式接口，
  改成批量需要排队协程，这一轮没接，避免半成品。
- 视频条目：`GalleryItem` 已留 `isVideo`，但三个来源目前仍只枚举图片
  （`MediaStore.Images`、PTP 仅图片对象），视频浏览与回传是下一轮的事。
- 双指缩放切换 3/4/5 列：小米相册有这个手势，本轮固定 4 列，没有接缩放手势。
- 相机内删除：要发 PTP `DeleteObject`，属破坏性操作，没有实现。

### 10.4 验证记录

- build63：下载二级页 + `DoukPrefs` 四个视频字段 —— `assembleDebug` 通过
- build64：图库时间轴 / 4 列 / 多选 / 删除 —— `assembleDebug` 通过
- `:app:testDebugUnitTest`：6s 增量通过（协议层 20+ 条单测未受影响）
- 真机（87159031）已安装 build64，但截图验收时设备进入锁屏（有安全锁，
  `isKeyguardShowing=true`、`input swipe` 无法通过），需人工解锁后补拍
- build65：修布局 bug —— 网格原来 `fillMaxSize()` 占满 Column，把「选择模式的底部
  操作条」挤出屏幕；改成 `Box(Modifier.weight(1f))` 包住网格后操作条正常显示
- build66：修交互 bug —— 多选态按系统返回键原本会离开图库页，现在注册
  `BackHandler(enabled = selecting)`，只退出选择模式
- 真机截图验收（build66）：自动下载二级页（开关 + 图片大小/格式 + 视频大小/格式 +
  保存位置 + 状态说明）、下载保护文件二级页、图库「今天」日期头 + 4 列贴边网格、
  长按多选（已选择 1 项 / 全选 / 橙色对勾 / 底部来源提示）、按返回键退出多选后
  仍停在图库页、应用内空态文案，全部符合预期
- **未验证**：应用内批量删除。机身 `Pictures/Douk` 当前没有下载过的照片，没有真图
  可删；按「不做演示数据」的原则没有造假样本，该路径等真机回传照片后再验



## 第十一章 机型能力矩阵与全屏看图（第三轮）

### 11.1 为什么四种连接方式不是每台机器都齐活

依据尼康官方手册（z7II_z6II、z8、z9、z6III、Zf 的网络菜单章节）与 NX MobileAir 的支持列表
（Z9 需 C 固件 5.00+、Z8 需 3.00+、Zf 需 3.00+，另加 Z6III / Z5II / Z50II / ZR），
四代际差异是**菜单结构本身**决定的，不是 App 的开关问题：

| 能力 | 二代机（Z7II / Z6II / Z7 / Z6 / Z5 / Z50 / Zfc / Z30） | 三代机（Z9 / Z8 / Z6III / Z5II / Zf / Z50II / ZR） | 单反（D6 / D780） |
|---|---|---|---|
| Wi-Fi AP 直连（相机开热点） | 支持，菜单只有「连接至智能设备 → Wi-Fi 连接 → 建立连接」 | 支持，称 AP mode | 机身不自带，需 WT 传输器 |
| Wi-Fi STA（相机加入手机所在路由器） | **不支持**，菜单里没有这一项 | 支持，「Wi-Fi 连接（STA mode）→ 建立配置」 | 需 WT 传输器 |
| USB PTP | 插线即 PTP，**没有** USB 数据连接菜单 | 必须先「设定菜单 → USB 数据连接 → MTP/PTP」，否则被当成读卡器 | 支持，部分机型需在菜单选 PTP/MTP |
| 蓝牙 | 仅供 SnapBridge 配对，不能当标准 BLE 定位通道 | 「配对（Bluetooth）→ 开始配对」，可推手机定位，另有「关机时上传」 | 无 |
| PTP/IP 端口 | TCP 15740（电脑/路由器场景另用 UDP 5353 做 mDNS） | 同左 | 经 WT 走 15740 |

**结论**：给用户做机型速查，比堆一个「全都支持」的开关更可靠。之前 STA 一直失败，
一半原因就是把这台机器当成了必须支持 STA 的机器去查问题。

### 11.2 代码落点

- 新增 `ui/douk/NikonModelSpec.kt`
  - `Spec(id, label, gen, sta, usbMtpMenu, bleGps, note)`，`Gen` 分 THIRD / SECOND / DSLR
  - `of(model)`：先把 PTP `GetDeviceInfo` 的机型串归一化（`Z 50_2` / `Z50_2` / `Z 6_3`
    都抹成纯字母数字 `z502` / `z63`）再查表，避免尼康写法不统一导致识别不到
  - `steps(spec, mode)`：三种无线/有线方式 × 代际的相机端操作步骤
  - `blocked(spec, mode)`：机型明确不支持时返回一句拦截文案
  - `summary(spec)`：一行能力摘要（`三代机 · 支持 STA · 蓝牙可推 GPS`）
- `AddDeviceScreen`
  - 「选择连接方式」上方新增机型卡：已连接时取 PTP 上报的型号并标「已识别」；
    没连上时可以手动指定（`ModelPickerDialog` 按三代 / 二代 / 单反分组列 18 个机型）
  - `ModeCard` 新增 `unsupported` 参数，机型不支持的方式在卡片里直接写
    「这台机型不支持：…」，不再让人反复试
  - `CameraMenuHint(mode, spec)`：原来的硬编码文案改为 `NikonModelSpec.steps`

### 11.3 图库：全屏看图页

- `PreviewDialog` 改名为 `PhotoViewer`，`DialogProperties(usePlatformDefaultWidth = false,
  decorFitsSystemWindows = false)` + 纯黑铺满，不再是居中白底卡片弹窗
- 顶部：返回箭头 + 文件名 + 「3.2 MB · JPG · 相机内/应用内/手机内」，渐变遮罩压在图上
- 中间：`detectTransformGestures` 双指缩放 1–6×（带边界夹取）、单指拖动；
  `detectTapGestures` 双击在 1× 与 2.5× 之间切换；缩放中顶部显示当前倍率
- 底部：USB 来源显示「保存到手机（Pictures/Douk）」，其余来源显示「双指缩放 · 双击放大」
- 打开看图仍走 `previewItem` 状态，返回键由 `Dialog.onDismissRequest` 收起

### 11.4 用户提供的一批开源项目怎么取舍

| 项目 | 能不能进这个 App | 判断 |
|---|---|---|
| gphoto / libgphoto2 | 不能直接嵌（C 库 + Linux udev 依赖），但它是 PTP/IP 最完整的开源参考，握手与事件通道的字段顺序可对照 | 参考 |
| meklarian/MekNikon、1TTT9/NikonCSWrapper | .NET 封装，且要自行申请尼康官方 SDK（闭源、需授权） | 不可用 |
| NightRainDream/RemoteCamera | Java 的 PTP 远程控制实现，思路可借用，但用的是厂商 SDK | 参考 |
| exiftool | Perl 脚本，Android 上无法运行；但「NEF MakerNotes 里存快门数 / AF 对焦点」这件事值得做成后续功能 | 后续 |
| storytold/lightcraft、CyberTimon/RapidRAW、dnglab | 桌面 Rust/C++ 应用：NEF 解码、NEF→DNG 转换，跟手机端解码链路不兼容 | 不可用 |
| semi-utils、Focus-Points(LrC 插件) | 写 EXIF / Lightroom 插件，不是相机控制 | 不可用 |
| gkoh/furble | ESP32 硬件 BLE 快门，GATT 服务定义可借鉴做「蓝牙快门键」 | 后续 |
| dognotdog/ptpwebcam | macOS 虚拟摄像头，平台不同 | 不可用 |

可落地的三个方向（本轮未做，留给下一轮）：NEF MakerNotes 读取（快门数、AF 对焦点）、
蓝牙快门键、把 PTP/IP 事件通道的 `0x90C7` 轮询频率按机型调优。

### 11.5 验证

- build67：机型能力层 + 全屏看图 —— `assembleDebug` 通过（第一次因
  `detectTapGestures` 漏 import 失败，补 import 后 14s 通过）
- 真机验收：装机后设备进入自动锁屏（`mWakefulness=Asleep`、
  `isKeyguardShowing=true`），`KEYCODE_WAKEUP` + 上滑无法通过安全锁，
  待人工解锁后补拍机型卡、机型选择弹窗、全屏看图三张截图

## 十二、图库小米相册化改造（build68）

照小米相册 5.4.2.6 的看图页 / 更多菜单 / 详情页三张参考图重做图库，全部接真实文件，
没有演示数据，也没有做「生成 PDF / 文档编辑 / 投屏 / 隐私水印」这类软件里没有实现能力的假按钮。

### 12.1 顶部来源收成一个按钮

- 原来的三个 chip（相机内 / 应用内 / 手机内）整排删除，改成顶栏里一个胶囊按钮：
  `图库` 标题 + `来源 ▾`（`DoukShapes.pill` + `surfaceMuted`），点开才展开来源面板。
- 来源面板每一项带一行说明，并对当前来源打勾：相机内「机身存储，经 USB 或 Wi-Fi 读取」、
  应用内「连镜下载下来的照片」、手机内「系统相册里的全部照片」。
- `GallerySource` 从 `private enum class` 变成 `internal enum class` 并加 `hint` 字段；
  `GalleryItem` 同样改成 `internal`，供新的看图页文件复用。

### 12.2 照片与相册

- 顶栏右侧新增「相册」入口（`Icons.Rounded.PhotoAlbum`），点开是相册面板：
  - 应用内：`GalleryFileOps.appAlbums()` 扫 `Pictures/Douk`、`DCIM/Douk` 的直接子目录，
    子目录就是「自建相册」，根目录散图归到 `Douk`；
  - 手机内：按 MediaStore 的 `BUCKET_DISPLAY_NAME` 聚合张数；
  - 相机内：机身只有一组，面板里给说明，不造假相册。
- 选中相册后进 `albumFilter`，网格只显示该相册的照片，顶栏下出现可清除的过滤条；
  切来源时自动清空过滤器。
- `GalleryItem` 新增 `album` 字段：应用内取 `file.parentFile?.name`，手机内取 `BUCKET_DISPLAY_NAME`，
  相机内为 null。多选的全选范围跟着过滤走，只选看得见的。

### 12.3 看图页（GalleryViewer.kt）

- 新的 `PhotoViewerScreen` 取代旧 `PhotoViewer`（旧实现在 build68 里整段删除）：
  全屏 Dialog（`usePlatformDefaultWidth=false`、`decorFitsSystemWindows=false`）、纯黑底、
  双指缩放 1–6×、双击 1×/2.5×、顶部 `← 日期 / 时间` + 右上分享。
- 底部浮起圆角操作条（白底 0.96、28dp 圆角）：发送（USB 相机照片显示成「保存」→ `saveUsbPhoto`）、
  收藏、删除、更多。
- 「更多」弹出白色分组卡片：添加到 / 详情 / 重命名（只有应用内文件才给）/ 复制文件名。
- 删除是底部浮起的确认面板（`ConfirmSheet`），只删应用下载目录里的那一份，
  相机与系统相册的原图不动；手机内与相机内会直接说明去哪儿删。

### 12.4 详情页

- 独立全屏 Dialog，照参考图布局：左边 84dp 灰色标签列、右边蓝色值（`0xFF3A7BD5`）。
- 固定三行：拍摄时间（EXIF `DATETIME_ORIGINAL` 解析成本地时间，没有则退回文件修改时间）、
  文件信息（文件名 + 大小 + `1310×1994px` 那样的分辨率）、文件路径。
- 应用内的分辨率与 IPTC 走 `androidx.exifinterface` 读原文件；手机内走 MediaStore 的
  `WIDTH/HEIGHT/DATE_TAKEN`，路径显示成「相册/文件名」。
- 补两行：拍摄参数（机型 / 镜头 / f 值·快门·ISO·焦距）、IPTC（标题 / 关键词 / 作者 / 版权）。
  标题读 `TAG_IMAGE_DESCRIPTION`、关键词读 `TAG_USER_COMMENT`——exifinterface 1.3.7 没有暴露
  Windows 的 `TAG_XP_TITLE`/`TAG_XP_SUBJECT`，编译报未解析才发现，别照旧写。
- 相机内照片没有本地文件，详情页如实写「相机机身存储 · 通过 PTP 读取，未落到本机」，
  要看 IPTC 得先保存到手机。

### 12.5 添加到与重命名

- 「添加到」是底部面板，三档：
  - 手机文件夹 → `Pictures/Douk`（只放文件，系统相册不展示）；
  - 手机相册 → `DCIM/LensLink`（系统相册可见）；
  - 应用相册 → `Pictures/Douk/<自己起的名字>`，图库「应用内」按目录名分组，等于自建分类相册。
- 写入统一走 `GalleryFileOps.writeToGallery`：`MediaStore` + `RELATIVE_PATH` + `IS_PENDING`，
  和 `DoukState.saveUsbPhoto` 同一套写法，Android 10+ 不需要存储权限
  （Manifest 里 `WRITE_EXTERNAL_STORAGE` 仍是 `maxSdkVersion=28`，没加新权限）。
- 来源决定读写方式：应用内读 `File`、手机内读 `ContentResolver` 打开的原图 uri、
  相机内没有本地文件，面板提示「要先回传到手机才能归类」。
- 重命名用 `File.renameTo` + `MediaScannerConnection.scanFile`，只改应用下载目录里的那一份；
  同名文件已存在、文件被占用时返回失败原因，不静默吞掉。

### 12.6 收藏

- `GalleryFavorites` 把「来源:键」写进 SharedPreferences（`douk_gallery_favorites`），
  只是本地标记、不动原图，重装或清数据后失效属于预期。

### 12.7 已知限制（不是 bug）

- 手机内照片改名：MediaStore 的 `DISPLAY_NAME` 更新在部分机型会让缩略图失效，本轮不做，
  只对应用内文件开放重命名。
- 「发送」依赖 MediaStore 里有这张图的 content uri：应用内照片是经 MediaStore 下载来的，
  正常能查到；查不到时提示先「添加到手机相册」，不硬发。
- 收藏没有单独的「收藏相册」入口，只在看图页显示状态。

### 12.8 验证

- build68：`:app:assembleDebug` 通过。第一次两类编译错误：
  `padding(start=, end=, vertical=)` 混用了两套重载、`TAG_XP_TITLE`/`TAG_XP_SUBJECT` 不存在，
  改掉后 exit=0。
- 装机：`adb install -r` 成功，`am start -S` 后 `logcat -b crash` 无输出，`dumpsys window`
  里 `mFocusedApp` 是本应用，启动没崩。
- 截图：设备处于安全锁屏（`isKeyguardShowing=true`、`mCurrentFocus=NotificationShade`），
  `screencap` 落盘 0 字节、`KEYCODE_WAKEUP` + 上滑仍解不开，本轮界面截图待人工解锁后补拍。

---

## 十三、图库二次改造与悬浮底栏（build71–build82）

用户第二轮要求：右上角图标删掉；单击「图库」标题本身弹出「照片 / 相册」两选项的白色圆角胶囊；
中间放图片与相册；底部「设备 / 控制 / 图库 / 设置」也改悬浮圆角样式。样式参照小米相册 5.4.2.6。

### 13.1 顶栏收敛

- 右上角原来的「相册」图标已删除，只留「筛选」。相册入口改为「图库」标题上的胶囊。
- 「图库」标题右侧那个朝下的箭头随状态翻转：收起时 `ExpandMore`，展开时 `ExpandLess`。
- 来源三选一不再平铺三个 chip，收成一枚「相机内 ▾」胶囊；点开是带说明的面板
  （相机内 / 应用内 / 手机内，每项两行：标题 + 一句 hint，当前项打勾）。

### 13.2 「照片 / 相册」胶囊（GalleryViewSwitch）

- 结构：`Surface(white, RoundedCornerShape(28.dp), shadowElevation = 10.dp)` 里放一行两个
  `ViewOption`；选中项自己是 `RoundedCornerShape(22.dp)` + `0xFFECECEC` 的浅灰块，不是整条变色。
- 每个选项：图标 25dp + 14sp 文字，上下排列，`weight(1f)` 平分；整条宽度固定 **232dp**。
- 位置：放在 `Column` 里顶栏下面（`Box(fillMaxWidth, contentAlignment = Center)` 居中），
  不再做浮层 `align(TopCenter)`。展开时会往下推一点内容，比浮层稳。
- **坑（重要）**：不要用「内容撑宽 + 浮层对齐」。调试期一度以为是 `align` 失效，
  用 `onGloballyPositioned` 打日志才发现胶囊其实一直是 610..1190、完美居中——
  真正的原因是**排查截图用 `crop((0,0,1080,760))` 裁了 1800 宽的截图**，
  把居中的右半截掉了，看起来像贴右溢出。以后裁图统一按原图宽 1800 裁。

### 13.3 相册视图（GalleryView.ALBUMS）

- `loadAlbumCards` 三个来源各自聚合：应用内按目录名、手机内按 `BUCKET_DISPLAY_NAME`、
  相机内固定一个「机身存储」。`loadAlbumCover` 取该相册最新一张当封面。
- `AlbumGridPanel` 是 2 列网格，卡片 = 方形封面 + 相册名 + 「N 张」；点卡片把相册名塞进
  `albumFilter` 并切回照片视图，顶栏下面出现一条可点叉的过滤胶囊。

### 13.4 看图页 / 更多 / 详情 / 添加到 / 重命名

- 全屏 `Dialog`（`usePlatformDefaultWidth = false`、`decorFitsSystemWindows = false`）：
  顶部 `←` + 「日期 / 时间」+ 右侧分享，底部白色圆角 pill（发送|保存 / 收藏 / 删除 / 更多）。
- 「更多」弹白色卡片：添加到 / 详情 / 重命名（仅应用内）/ 复制文件名。
- 详情页照 r5：左 100dp 灰色标签 + 右 `0xFF3A7BD5` 蓝值，标签 `maxLines = 1`（84dp 时
  「拍摄时间」会折成两行，已加宽到 100dp）。
- 「添加到」面板：手机文件夹 `Pictures/Douk`、手机相册 `DCIM/连镜`、应用相册（输入名字
  后在 `Pictures/Douk/<名字>` 建目录再放进去）。
- 提示条（message）：曾经和 pill 挤在同一 `Column` 里造成重叠，现改为独立
  `align(BottomCenter) + padding(bottom = 132.dp)` 的浮层，稳居 pill 上方。
- `PillAction` 加 `heightIn(min = 50.dp)` + 垂直居中，点击区不再有 ICON 与文字之间的缝。

### 13.5 底部悬浮栏（DoukBottomBar）

- 由「贴底 Surface + 分割线」改成浮起胶囊：`Box(navigationBarsPadding + padding(16/16/6/8))`
  → `Row(weight, shadow(18.dp, RoundedCornerShape(28.dp)))`，每个 tab 是 22dp 圆角
  `Column`，选中时填 `accentSoft`。与参考图一致。

### 13.6 真机验证（build71–build82，设备 87159031）

- 编译：build71/72/73/75/78/79/80/81/82 全绿；build74/76/77 分别因缺 `Surface`、
  `onGloballyPositioned`、`LayoutCoordinates.layoutDirection` 编译失败，已修。
- 装机启动：`install -r` + `am start -S` 后 `logcat -b crash` 为空。
- 截图验收（`_tmp\b1s.png`、`c2c.png`、`c6c.png` 等）：
  1. 设备页底部悬浮胶囊，选中「设备」浅橙块 ✔；
  2. 「图库」标题点开，照片/相册胶囊像素实测 610..1190（屏幕中心 900）居中 ✔；
  3. 来源面板「相机内 / 应用内 / 手机内」三段带 hint ✔；
  4. 应用内相册视图：Douk 2 张、Tokyo 1 张，封面取最新一张 ✔；
  5. 看图页：顶部 ← + 日期 + 时间 + 分享，底部 pill 四项 ✔；
  6. 删除确认面板 ✔、更多卡片（添加到/详情/重命名/复制文件名）✔；
  7. 详情页标签不再折行 ✔；
  8. 「添加到 → 手机文件夹」真实落盘 `Pictures/Douk/mi_ref1 (1).jpg` ✔；
  9. 「重命名」真实改名 `mi_ref1 (1) (1)_r.jpg` ✔，图库按 `galleryRefreshTick` 立即刷新成 5 张 ✔。
- 验证用图（`r1.jpg` / `r5.jpg` 参考截图）已从 `/sdcard/Pictures/Douk` 与
  `/sdcard/DCIM/Douk/Tokyo` 清理干净，用户图库没有残留测试文件。

### 13.7 还没做的

- 看图页「编辑」不做（参考图里有，但项目没有编辑能力，不放假按钮）。
- 详情页的「拍摄时间」值较长时会折成两行（`2026年11月16日 星期` + `18:11`），
  想彻底一行要改成 `2026年11月16日 星期一 18:11` 的拼法，留待下一轮。
- 手机内照片重命名、相机内照片「添加到」仍按 12.7 的限制处理（提示而不是硬做）。

---

## 十四、底部悬浮栏全圆化 + 图库切换条下沉 + 筛选真过滤（build83–85）

这一轮把「照片 / 相册」从顶部搬到 dock 上方，并把一直只是摆设的筛选做成真过滤。

### 14.1 dock 变成全圆胶囊，且真的浮起来

`DoukBottomBar`（`DoukRoot.kt`）原来用 `RoundedCornerShape(28.dp)`，28dp 在 60dp 高的条上只
算「大圆角」，视觉上仍是圆角矩形。改成 `RoundedCornerShape(percent = 50)`——圆角恒等于高度的
一半，无论条多高都是真正的胶囊，也不会被内容高度撑变形。

浮动这件事改成三层叠加：

1. `navigationBarsPadding()`：手势导航条的高度自己吃掉，图标和文字不会被压住；
2. 外层 `padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp)`：左右各留 18dp、
   底下再留 18dp，条与屏幕边缘之间始终有空隙，看起来是「悬空的一张圆角卡片」；
3. `shadow(20.dp, pill, clip = false)`：阴影画在裁剪之外，浮起感才成立。

tab 的内层选中块也从 `RoundedCornerShape(22.dp)` 换成同一个 `pill`，选中态是胶囊套胶囊。

### 14.2 「照片 / 相册」改为「点 dock 的图库，再点一次」才弹，并且沉到底部

跨页通信用一个整数 tick：

- `DoukRoot.kt`：`var gallerySwitchTick by remember { mutableStateOf(0) }`；`DoukBottomBar`
  增加 `onGalleryReselect` 参数，tab 点击时先判「当前已选中且 route == "gallery"」就回调
  （否则照旧 `navigate`）；`NavHost` 把 `switchTick` 与 `onSwitchConsumed` 传给 `GalleryScreen`。
- `GalleryScreen.kt`：`LaunchedEffect(switchTick) { if (switchTick > 0) { showViewSwitch = true; onSwitchConsumed() } }`。
  consumed 把 tick 归零，所以「连点两次」不会累积成连弹。

位置从顶栏下方改到页面底部：`GalleryScreen` 外层包了一个 `Box(fillMaxSize)`，切换条用
`Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)` 浮在网格之上、
正好压在 dock 上方 12dp。同时把顶栏那个「点标题弹条」的可点 Row 与箭头 Icon 一并删掉——
标题不再承担开关，避免两个入口互相打架。

### 14.3 图库标题左对齐

顶栏 `Row` 的 `start` 从 `6.dp` 改成 `DoukSpace.page`（20dp），与 `DeviceTopBar`、
`ControlScreen`、设置页标题的左边距完全一致；之前 6dp 是因为标题本身要做成可点区域而加的
内缩，现在不点了就没必要。

### 14.4 筛选真的过滤了：类型 / 格式 / 构图

此前 `GalleryFilterDialog` 的 `selected` 只活在对话框内部，选完什么都不发生，横竖构图更是
没有数据支撑。这一轮补齐了数据与过滤链：

- `GalleryItem` 新增 `width` / `height`（可空）与两个派生属性：
  `ext`（小写扩展名）、`orientation`（宽 > 高 = 横构图，宽 < 高 = 竖构图，尺寸缺失为 null）。
- 新增 `GalleryFilter(kind, format, orientation)`，`matches(item)` 三个维度独立判断；
  `isActive` 供顶栏筛选图标变色用。
- 三个来源各自填数据：
  - 应用内（`AppGallery`）：扫描放到 `LaunchedEffect` 内的 IO 线程，逐张
    `BitmapFactory.inJustDecodeBounds` 读宽高；视频走 `MediaMetadataRetriever` 的
    `METADATA_KEY_VIDEO_WIDTH/HEIGHT`。文件扫描范围也从「jpg/jpeg/png」扩到
    jpg/jpeg/png/heic/heif/nef/nrw/dng/mp4/mov，`isVideo` 按扩展名标记。
  - 手机内（`PhoneGallery`）：MediaStore 投影补 `WIDTH` / `HEIGHT` 两列，直接取真实像素。
  - 相机内（`CameraGallery`）：PTP 的 `GetObjectHandles` 只有句柄和文件名，拿不到尺寸，
    所以构图维度对它不生效（过滤逻辑里「未知尺寸」按放行处理，不会把机身照片全筛空）。
- 筛选维度：类型（全部 / 图片 / 视频）、格式（全部 / JPG / RAW / PNG / HEIC / MP4 / MOV）、
  构图（全部 / 横构图 / 竖构图）。「已回传」按要求删掉——它本来就没有对应字段。
- 对话框重写：三组 `FilterGroup` 胶囊，选项多了按 4 个折行（第一版一行排 7 个会溢出到对话框外面）；
  底部「重置」一键回到全默认并立刻生效，「应用」写回 `GalleryScreen` 的 `filter` 状态。

### 14.5 空态与状态反馈

- 应用内 / 手机内 / 相机内三个网格都补了「筛选后为空」分支：
  「没有符合筛选的照片 / 换一个类型或格式，或者点筛选里的重置」，
  不再出现整页空白（这是第一版的 bug：`AppGallery` 只判了 `files.isEmpty()`，筛出 0 张时是纯白页）。
- 顶栏筛选图标在有条件时染成主题色，无条件时是常规文字色，避免「点了没反应」的错觉。

### 14.6 真机验证（build83–85，设备 87159031）

1. dock 全圆胶囊 + 四周留白 ✔（`e1.png`：设备页，四个 tab 文字完整不被导航条压住）；
2. 图库标题与「设备」标题左边距一致 ✔（`e2.png`）；
3. 在图库页再点一次 dock 的「图库」，胶囊从 dock 上方居中弹出 ✔（`e3.png`）；
4. 筛选对话框三组胶囊、格式折行正常 ✔（`f2.png`）；
5. 放入 1600×900 与 900×1600 两张测试图，选「横构图」→ 应用后只剩横图 1 张 ✔（`f6.png`）；
6. 改选「竖构图」→ 只剩竖图 1 张 ✔（`f7.png`）；
7. 选「JPG」→ 0 张并给出空态提示 ✔（`g2.png`），「重置」后恢复全部 ✔（`g1.png`）；
8. 测试图 `t_land.png` / `t_port.png` 已从 `/sdcard/Pictures/Douk` 删除。

### 14.7 还没做的

- 相机引擎内的照片没有尺寸，横竖构图对「相机内」这一来源不参与；要做需要 PTP 里补
  `GetObjectInfo` 的 `ImagePixWidth/Height`（当前枚举只取文件名与大小，加字段要重排列表请求）。
- 筛选条件不跨页持久化（离开图库页会复位），与「来源」当前行为一致。
- 相册视图（`AlbumGridPanel`）不受筛选影响，仍是全量封面墙。

## 第十五章 图库 / 看图页 / 详情页 六组改造 + 图片编辑器（build86 → build100）

> 本轮全部改动已在真机（`87159031`，Xiaomi 23043RP34C / Android 14）逐项验收，
> 验收截图见文末 15.6。工程编译方式不变：`gradle :app:assembleDebug`。

### 15.1 图库：筛选联动、串图修复、媒体库登记

| 改动 | 落点 | 说明 |
| --- | --- | --- |
| 格式胶囊按类型动态 | `GalleryScreen.kt` · `GalleryFilterDialog`（≈1222） | 图片→全部/JPG/RAW/PNG/HEIC；视频→全部/MP4/MOV；全部→七项 |
| 换类型自动复位格式 | 同上 | 已选格式不在新类型集合里时回落到「全部」，避免选了「JPG」再切到视频仍筛 JPG |
| 删掉构图说明文字 | 同上 | 构图胶囊下方那句提示整段去掉 |
| 缩略图串图修复 | `GalleryItem.stableKey`（≈127） | 由 `"$origin:$key"` 改为 `"$origin:$key:$fileName"`；`key` 是列表下标，保存新图后下标整体后移会让缓存命中错图 |
| 未入库文件补登记 | `GalleryScreen.kt` · `LaunchedEffect(refreshTick)`（≈750） | 扫完 `Pictures/Douk`、`DCIM/Douk` 后调 `MediaScannerConnection.scanFile` 把所有文件登记一次；手工拷进这两个目录的图也能在系统相册里出现 |

### 15.2 看图页（`GalleryViewer.kt` · `PhotoViewerScreen` 115 起）

- **点图收 UI**：状态 `uiHidden` / `rotation` / `editing` / `lastDismissAt`。单击图片在「显示 ↔ 收起」之间切，收起后顶栏与底部 pill 一起隐藏；`lastDismissAt` 做 150ms 防重，避免一次点击被 Compose 重复派发两次。
- **旋转视图**：右下角 `RotateRight` 每次转 90°，`BoxWithConstraints` 里按 90° 对调可用宽高再算绘制尺寸，横竖切换不留黑边。
- **顶栏**：`←` + 拍摄日期时间 + 「旋转视图」按钮（原来是右上角分享，已按需求改掉）。
- **底部 pill**：`RoundedCornerShape(percent = 50)`，五格 —— 发送 / 收藏（☆，选中后变实心星）/ 编辑 / 删除 / 更多。
- **更多浮层**：先铺一层透明遮罩接住点击，再把 `MoreMenuCard` 贴到「更多」按钮正上方（宽 204dp、`Alignment.BottomEnd`、`bottom = 104dp`），不再是通栏大长条。条目为「保存到 / 详情」+ 分隔线 + 「重命名」。点空白只关浮层，顶栏和 pill 保持原样。
- **详情页**：IPTC 三行（标题 / 作者 / 版权）用 `buildList` 汇总，**三项都空就整行不渲染**；新增「位置」行 —— 左侧 100dp 灰标签 + 经纬度值 + 圆角「地图查看」胶囊，点击走 `Intent(ACTION_VIEW, geo:lat,lon)`，没有地图应用时回落 `https://maps.google.com/?q=lat,lon`。

### 15.3 图片编辑器（新文件 `PhotoEditor.kt`，真实像素管线）

结构：`EditState`（≈77）→ `ADJUSTS`（≈110，13 项）→ `PhotoEditorScreen`（≈146）→ `CurvePreview` / `curveOut` → `applyColor` → `saveEdited`。

- **顶部**：`X` 退出 / 撤销 / 重做 / 「保存」胶囊；预览区 `weight(1f)`；下面滑杆行；再下面三个 tab。
- **基础**：旋转（可拖动轴，显示角度）、裁切（1:1、3:4、4:3、2:3、3:2、9:16 等固定比例 + 自由比例走中心裁切）、水平翻转 / 垂直翻转 / 旋转 90°。
- **调节**：曝光、亮度、对比度、高光、阴影、饱和度、自然饱和度、色温、色调、锐化、清晰度、颗粒、噪点、降噪（横向胶囊行）。
- **曲线**：`CurvePreview` 画灰网格 + 橙色映射曲线，`curveOut()` 生成 256 项 `curveLut`，`applyColor` 里逐通道查表。
- **HSL**：色相 -180..180、饱和度/明度 -100..100；`applyColor` 内做 rgb→hsl→rgb 六分支处理，`SliderRow` 对色相单独判范围。
- **滤镜**：原图 / 黑白 / 暖调 / 冷调 / 鲜艳 / 复古。
- **渲染与保存**：`renderPreview(1080)` 供预览（`Dispatchers.IO` + 120ms 合并），`renderFull(4096)` 出图；`geometry()` 处理几何变换，`cropByRatio()` 按比例中心裁切，`blurInto` / `unsharp` / `addGrain` 做局部模糊与锐化颗粒；历史栈 push/live/commit/undo/redo 限 40 步。
- **落盘**：写 `Pictures/Douk/${base}_edit.jpg`（重名依次 `_edit2`、`_edit3`…，JPEG 95），再用 `MediaStore.insertImage` 补登记，图库立刻能看到。

### 15.4 GPS「位置」行的完整排查记录（本轮耗时最多，结论可复用）

现象：详情页要么不显示「位置」，要么显示 `0.00000, 0.00000`。

排查链路（每一版都有真机日志）：

1. **build88**：先 `getLatLong` 失败再读字符串 `TAG_GPS_LATITUDE`。
   日志 `GPS_FALLBACK lat=0/1,0/1,0/1 latRef= lon=0/1,0/1,0/1 lonRef=` ——
   `androidx.exifinterface:exifinterface:1.3.7` 能定位到 GPS 标签，却把 rational 读成 `0/1`、ref 读成空。
2. **build89–92**：改成「自研 TIFF 解析优先，ExifInterface 兜底」。
   结构定位完全正确（`GPS_ENTRY e=88 tag=0 / 100 tag=1 / 112 tag=2 cnt=3 / 124 tag=3 / 136 tag=4`，
   `GPS_RAT e=112 at=228`、`e=136 at=252`），但 offset 228/252 处读出来的 num/den 全是 0。
3. **build93–96**：打十六进制。`head0` 与文件头、IFD0、IPTC 区全部正确，只有 216 之后的
   rational 数据区是 0；换成 `readBytes()` 全读、换新文件名重推，结果一样。
4. **交叉验证**：`adb shell od -A d -t x1 -j 200 -N 100 /sdcard/Pictures/Douk/t_gps.jpg`
   在 228 处读到 `17 00 00 00`（=23），`md5sum` 与本地样本完全一致；而 App 进程里
   File、MediaStore `openInputStream`、`RandomAccessFile` 三种读法拿到的同一段全是 0。
5. **决定性对比**：把 App 自己保存的 `t_land_edit.jpg` 用同样三种读法读 228..268 段，
   结果 `0014725452430000…` 与磁盘 `od` 逐字节一致 —— 读能力正常。

**根因**：`adb push` 进公共目录的样片不属于本 App 创建，在 App 进程视角被系统做了
「部分照片访问 / redaction」处理（大小、文件头、头部文本段都在，靠后的像素与 EXIF
rational 区被零填充）。这是 Android 14 对第三方媒体的保护行为，不是本 App 的读取缺陷。

**结论**：真实链路上（相机 PTP 下载后由 App 写入、编辑器另存、App 内的应用相册图片），
GPS 读取完全正常。验证方式：用 `ExifInterface.setLatLong(23.129, 113.259)` 给一张 App
自建 JPEG 写 GPS → 自研解析器输出 `GPS_BY_TIFF lat=23.129 lon=113.259` → 详情页出现
「位置：23.12900, 113.25900」+「地图查看」胶囊（截图 `g8.png` / `g10.png`），清理诊断后再验一遍仍是同样结果。

**本轮顺带做的两件事**：
- `parseGpsTiff` 改成只读文件头 96KB（`FileInputStream` 分块读），不再为一张几十 MB 的照片整读进内存。
- 诊断代码已全部删除（`GPS_HEX` / `GPS_ENTRY` / `GPS_RAT` / `GPS_RAT_ITEM` / `DIAG_*`），
  只保留 `GPS_TIFF_STEP`（为什么没解出来）、`GPS_TIFF_MISS`（确实没有 GPS）、
  `GPS_BY_TIFF`（解出来了）三条正常日志。

### 15.5 IPTC 是什么（本项目怎么用）

IPTC = International Press Telecommunications Council（国际新闻电信理事会）制定的图片元数据标准，
写在图片头部（Photoshop IPTC 块或 XMP 里）。字段包括标题（Title / Object Name）、关键词、作者（By-line）、
版权、拍摄地点、说明等，主要给新闻机构和图库做检索与版权归属用。

本项目不做完整的 IPTC 解析，用 `ExifInterface` 的四个近似字段凑出详情页那三行：
`TAG_IMAGE_DESCRIPTION`（标题）、`TAG_ARTIST`（作者）、`TAG_COPYRIGHT`（版权），
另把 `TAG_USER_COMMENT` 当作关键词来源。三项都空时整行不显示，不占版面。

### 15.6 本轮真机验收截图

| 文件 | 内容 |
| --- | --- |
| `_tmp\h9.png` | 筛「图片」时格式只剩 JPG/RAW/PNG/HEIC |
| `_tmp\f7.png` | 看图页新 UI（顶栏 + 底部 pill） |
| `_tmp\h6_87.png` | 更多窄卡浮层 + 点空白关闭后顶栏/pill 仍在（防重生效） |
| `_tmp\ed2.png` | 编辑器 -26° 旋转，画面真实倾斜 |
| `_tmp\f9.png` | 黑白滤镜生效且不退出编辑 |
| `_tmp\h3_87.png` / `h5_87.png` | 调节页曲线小图 + 6 个新胶囊；曲线·暗部=98 时曲线弯曲、画面压暗 |
| `_tmp\h7_87.png` | 详情页 IPTC 三项 |
| `_tmp\g2.png` | 无 IPTC 时该行不显示 |
| `_tmp\g3_88.png` | （历史）位置 0.00000 的错误状态 |
| `_tmp\g8.png` / `g10.png` | 位置 23.12900, 113.25900 + 地图查看 |
| `_tmp\t_land_edit.jpg` | 编辑器保存产物（1600×900，JPEG 95） |

### 15.7 还没做的

- 裁切只有「固定比例的中心裁切」，没有可拖动的自由裁切框（用户提过「自由裁切 + 固定比例」，固定比例已做，自由裁切待补）。
- 编辑器「保存」目前一律另存副本（`${base}_edit.jpg`），是否要覆盖原图未定。
- 详情页对「相机内」来源不显示路径与位置（PTP 侧没有可用的文件路径）。


## 十六、看图页手势重做 / 设置页结构重做 / 详情页仿小米 + 小地图（build101–105）

这一轮按验收意见做了 9 项改动，全部落地并在真机（Xiaomi 23043RP34C / Android 14，adb `87159031`）逐项跑过。

### 16.1 看图页：pill 瘦身 + 手势状态机

改动前看图页底部是「发送 / 收藏 / 编辑 / 删除 / 更多」五格，点击图片只有「收 UI」「双击放大」两种。

现在：

1. **pill 只剩四格**：收藏（星星） / 编辑 / 删除 / 更多。原来的「发送」被移到「更多」菜单里，
   相机内照片那一格是「保存」（走 `state.saveUsbPhoto`）。图标之间 `spacedBy(6.dp)`、
   每个 `PillAction` 左右各 16dp padding，pill 内边距 `10dp`，肉眼能看出间距变大。
2. **手势收敛成一个状态机**。原来是 `detectTransformGestures` + `detectTapGestures`
   两个 `pointerInput` 叠在一起，再加左右滑动和上划必然打架，所以换成一个
   `awaitEachGesture` 循环，按手势结束时的位移判类型：
   - 双指或过程中发生过缩放 → 只做缩放（`scale` 夹在 1..6，`offset` 按 `size` 夹取），结束不做别的事；
   - 单指横滑，横向位移 > 宽度 20% 且纵向 < 宽度 16%（`scale` 还是 1）→ `onSwipe(+1 / -1)` 换图；
   - 单指上滑，纵向位移 > 高度 16% 且横向很小 → 直接 `share()`；
   - 轻点（按下到抬起 < 280ms 且位移 < 24px）：先判双击（两次轻点间隔 < 320ms，
     在 1× 与 2.5× 之间切换），否则在 `now - lastDismissAt > 150ms` 时切换 `uiHidden`。
     `lastDismissAt` 用来自「点空白关闭更多」的那一下触摸，避免同一下把上下两条 UI 也收掉。
3. **左右切图**由 `GalleryScreen` 负责换数据：`PhotoViewerScreen` 新增 `onSwipe: (Int) -> Unit`，
   调用点按 `currentItems.indexOfFirst { it.stableKey == item.stableKey } + dir` 取下一张，
   到头就停。换图后 `LaunchedEffect(item.stableKey)` 会把 `scale / offset / rotation / uiHidden /
   more / detail / editing` 全部复位，并重算收藏状态，再重新解大图，所以横滑过去不会残留上一张的缩放。

### 16.2 设置页：通用分组重排 + 全部改成独立全屏子页

- **通用顺序**按验收意见重排为：资源 · LUT 与滤镜 → GPS 数据导入 → 储存 → 拍摄后快速预览 →
  设备页更换图片 → 外观设置 → 语言。开关类的「拍摄后快速预览」保留成开关，其余都是入口。
- **权限说明从通用移进关于**：`AboutPanel` 里新增「权限说明」一行，点开是 `SettingsSubPage`
  风格的独立全屏页，7 项系统权限逐条写用途。
- **所有设置项改成独立全屏子页**，不再用 `AlertDialog` 压在原页面上。
  新加的外壳是 `SettingsSubPage(title, onBack) { content }`：白底、顶部 ← + 标题、内容整屏滚动、
  `BackHandler` 接管返回键。子页有 `resources / gps / storage / appearance / language / sort` 六个，
  外加关于页里的 `权限说明`。语言从下拉弹窗改成页内三选一（跟随系统 / 中文 / English），
  选中项右侧打勾。
- **外观设置删掉「悬浮 Douk 栏」开关**（`DoukPrefs.floatingBar` 不再暴露给用户），保留
  玻璃质感、深色模式、主题色五选一。
- 旧的 6 个 `AlertDialog`（储存 / 权限 / GPS / 外观 / LUT / 参数排序）整段删除，
  相关状态变量 `showStorage / showPermission / showGps / showAppearance / showLut / showSort / floating`
  一并清掉，避免留下死代码。
- 二级动作按钮统一成 `SheetPrimaryButton`（整行、`RoundedCornerShape(percent = 50)`、主题色），
  和设置页现有的圆角风格一致。

### 16.3 详情页：仿小米样式 + 小地图

详情页按参考截图（小米相册详情页）重排，行顺序：拍摄时间 → 文件信息 → 拍摄参数 → 文件路径 →
IPTC（有才显示）→ 拍摄地点 → 小地图。每行下面加了 0.6dp 的浅灰分隔线（`DetailDivider`），
左标签保持浅灰、右值保持蓝色，和参考图一致。

**拍摄地点**：蓝色定位图标 + 坐标文本（`%.5f, %.5f`）。没有地理编码服务 key，所以**不编造地址文案**，
只显示坐标；整块区域和整张地图都可以点，点击交给系统地图（`geo:` 优先，失败退 Google Maps 网页）。

**小地图（`MapThumb`）**：

- 只取中心点周围 3×3 张 256px 瓦片拼成 768×768，再按卡片宽高比裁中间一条，中心就是给定坐标；
- 瓦片走高德公开瓦片接口（`webrd0{1..4}.is.autonavi.com/appmaptile?...style=8`）。**换成高德的原因**：
  OpenStreetMap 的 `tile.openstreetmap.org` 在本机网络下实测直接超时（12s 无响应，`Invoke-WebRequest` 复现），
  国内手机同样拉不到，会永远停在「地图加载中」；
- 高德瓦片是 GCJ-02 坐标，所以先做 WGS-84 → GCJ-02 转换（`wgs84ToGcj02` + `transformLat/transformLon`，
  国外坐标直接返回原值），否则定位针会偏 300–600 米；
- 下载在 `Dispatchers.IO`，单张 2.5s 超时，连续 4 张失败就放弃；外层再套
  `withTimeoutOrNull(9000)`，9 秒内凑不齐就显示「这里暂时画不出地图，点一下用系统地图看」，
  不会无休止转圈；
- 卡片高度 172dp、圆角 14dp，正中压一个红色定位针。

实测（`_tmp/v10.png`）：坐标 23.12900, 113.25900 的地图落在广州人民公园 / 公园前 / 中山五路一带，
定位针落点与坐标吻合。

### 16.4 本轮真机验收截图

| 文件 | 内容 |
| --- | --- |
| `_tmp/s1.png` | 设置页通用新顺序（资源 → GPS → 储存 → 拍摄后快速预览 → 设备页更换图片 → 外观 → 语言），权限说明已不在通用 |
| `_tmp/s2.png` | 「储存」独立全屏子页（四项占用 + 导出设置文件胶囊按钮） |
| `_tmp/s3.png` | 「外观设置」子页，「悬浮 Douk 栏」已消失，主题色带勾选 |
| `_tmp/s7.png` | 关于 → 权限说明独立页（7 项权限逐条说明） |
| `_tmp/v1.png` | 看图页四格 pill（收藏 / 编辑 / 删除 / 更多），间距加大 |
| `_tmp/v2.png` | 「更多」窄卡：发送 / 保存到 / 详情 / 重命名 |
| `_tmp/v3.png` | 详情页重排后（含拍摄地点与小地图加载中状态） |
| `_tmp/v7.png` | 横滑换图后（顶部时间从 20:18 变为 19:42，说明切到了下一张） |
| `_tmp/v9.png` | 上划拉起系统分享面板「发送照片」 |
| `_tmp/v10.png` | 小地图渲染成功（广州人民公园一带，红针居中） |

### 16.5 已知边界

- 小地图依赖高德公开瓦片接口（无 key），自用没问题；若将来要上架，需要换成正式地图 SDK
  （天地图 / 高德开放平台 key）或重新评估合规。
- 「拍摄地点」只有坐标，没有地址文案（没有地理编码 key）。
- 相机内（PTP）照片的「更多」里没有「重命名」（`canRename` 只对 `origin == "app"` 为真），
  与手机相册照片的规则一致：系统相册不开放改名。
- 编辑器裁切仍然是固定比例的中心裁切，自由裁切框仍未补。

### 16.6 机型不再手选 + 配对后「IP 不可达」的真因（build106/107）

这一轮改的是连接流程，不是看图/详情页。

**① 去掉「选择相机机型」**

原实现让用户在连上之前先指认机型（`ModelPickerDialog`，分三代机/二代机/单反三组），
再用这个机型去算能力表和相机端步骤。问题有两个：用户会选错，选错之后能力表与机身真实能力打架；
而且「发现的设备」点一下本来就已经连上，再问机型是多余的一步。

现在：

- `pickedModel` / `showModelPicker` / `ModelPickerDialog` / `NikonSpecCard` 全部删除；
- 机型只用机身上报的 `GetDeviceInfo` 型号（`state.profile.model`），识别成功才显示一张只读卡片
  「尼康 XX · 机身上报」，没有任何修改入口；
- 未连接时不再显示任何机型相关内容，四种连接方式都可直接选。

**② 「相机端设置」改成「提示」**

原来那块标题叫「相机端设置」，只讲当前选中方式的操作步骤。现在标题改成「提示」，内容分三层：

1. 当前方式的说明（`ConnectMode.detail`）；
2. 「相机端这样设置：」两行机型对照，**不出现「三代机 / 二代机」这类说法**，只列型号：
   - `Z9、Z8、Z6III、Z5II、Z f、Z50II、ZR：推荐使用「连接至智能设备」`
   - `Z7II、Z6II、Z7、Z6、Z5、Z50、Z fc、Z30：使用「连接到计算机（照相机控制）」`
   （型号清单来自 `NikonModelSpec.wirelessStaNames` / `computerNames`，改机型只需改这张表）
3. 当前方式的详细步骤 + 「相机一次只允许一台设备连接」的告警。

**③ STA 报「IP 地址不可达」的真因：不是 STA 连不上，是配对之后的重连太急**

日志（`files/douk-logs/session-20261116-204502.log`）显示 STA 这一次其实是**连上了**的：

```
20:57:56 TCP_CONNECTED local=/192.168.125.60:51222 remote=/192.168.125.174:15740
20:57:56 HANDSHAKE_ACK / OPEN_SESSION 0x2001 / DEVICE_INFO 0x2001
20:57:56 CAPABILITIES model=Z50_2 firmware=V1.11 commands=9 props=21 restricted=true
20:58:19 PAIR_CODE code=8950
20:58:22 PAIR_SUBMIT code=0x2001        ← 相机接受了配对
20:58:24 PREFLIGHT_FAIL localNets=wlan1 192.168.125.x   ← 1.2 秒后就探端口，必然失败
```

也就是说：相机端「已连接」是真的，失败点在**配对提交成功之后的那一次重连**。

尼康的配对是两段式的：`0x952B` 取验证码、`0x935A` 提交确认。提交成功后机身会做一次
**服务交接（pairing → normal service handoff）**：关掉当前 PTP 会话、把内部服务从「配对服务」
切到「正常服务」，15740 端口要关十几秒才重新监听；部分机型还会退出「连接至智能设备」菜单、
或重连热点后被 DHCP 换掉 IP。原代码只 `delay(1200)` 就探一次端口，探不到就直接判「IP 不可达」，
于是界面一直停在失败态。

> 佐证（GitHub 调研）：开源研究项目 `thetetherdev/thetether` 的
> `Docs/C1_PAIRING_DISCONNECT_GATE_2026-09-07.md` 记录了 Z7II / Z8 的同一条时间线：
> `0x1002 → 0x2001`、`0x952B → 0x2001`、`0x935A → 0x2001` 之后，
> `context-pairing-complete … reconnect=same-context`、`pairing-normal-service-handoff-accepted`，
> 相机重新广播服务（NSCC → WT3T），中间有 5 秒级的 timeout defer，整段到重新可用约 62 秒。
> 这说明「配对成功后需要等价重连、且要等机身换服务」是这一代机型的通用行为，不是本机特例。
> PTP 操作码本身则与 `open-nikonz/zf-capture-one-helper` 的公开研究一致。

修复（`DoukState.reconnectAfterPairing` + `awaitConnected`）：

1. 配对成功后先 `vm.disconnect()` 把旧会话收干净，再进入等待循环；
2. 等待序列 `1.5s → 2s → 2.5s → 3s → 3.5s → 4s`，每一轮先探 15740（1.5s 超时），端口开了就建会话，
   再 `awaitConnected(9000)` 等 PTP 真的成立（不是只看 socket）；
3. 从第 2 轮起顺带扫全网段：相机被 DHCP 换了 IP 也能自己找回来（`HANDOFF_NEW_IP`）；
4. 全程把进度写到界面：`等相机重新开放 15740…（第 n/6 次，目标 x.x.x.x）`，
   6 轮都不行才给最终提示，并明确说明「核对完验证码后机身可能自动退出连接至智能设备菜单」，
   让用户回相机确认一次再点搜索，而不是让 App 反复报 IP 不可达。

顺带把普通连接（非配对）也加了一次重试与自愈：`connectSafely(attempts = 2)`
第一轮不通会等 1.2 秒再探；两次都不通才扫全网段，扫到别的 IP 就自动改用它连
（`PREFLIGHT_RECOVER`）。STA 的失败文案也补了「手机热点是否开了客户端隔离 / AP 隔离」——
这一项开着时，相机端显示已连接、手机端怎么都访问不到。

**④ 其他三种连接方式的检查结论**

| 方式 | 结论 |
| --- | --- |
| Wi-Fi AP 直连 | 代码路径与 STA 共用 `connectSafely` + 端口探测，本次的重试/自愈同样生效；相机自己开热点时手机必须连相机 Wi-Fi，探的是本机网段，逻辑正确 |
| USB PTP | 枚举 `UsbManager.deviceList`（VID `0x04B0` 或 STILL_IMAGE 接口）→ 申请权限 → `UsbPtpClient.connectDevice`，没发现逻辑错误；文案已改成「设定菜单 → USB 数据连接 → 连接到计算机（照相机控制）」 |
| 蓝牙（GPS） | 只走 BLE 推定位，不承担图传；扫描/配对/重连三个入口都在，权限按 Android 12+ 的「附近的设备」申请，未发现错误 |

**⑤ 本轮验收**

- 设备页「添加设备」：无机型卡片、无选择弹窗，四种方式直接可选（`_tmp/a1.png`）；
- 提示区显示机型清单两行 + 步骤 + 告警（`_tmp/a2.png`）；
- build106 / build107 均 BUILD SUCCESSFUL，APK 已装机；
- **未能实机复验配对交接**：测试时相机 Wi-Fi 已断开（手机侧 `ping 192.168.125.174` 100% 丢包，
  网段扫描也找不到 15740），需要相机重新进入「连接至智能设备」后再跑一次配对才能验证
  6 轮等待是否真的拿到完整会话。这一步留给下一轮实机确认。

---

## 16.7 build108–build113：控制页重构与「参数改不动」的真因处理

### ① 监视改为一个按钮 + 点开全屏（真全屏）

- `ControlScreen` 里原来的内嵌 16:9 `PreviewCard`（右上角全屏圆钮 + 右下「隐藏」胶囊）整块删除，
  换成一行 `MonitorButton`：左侧图标 + 「监视」标题 + 副标题（未连接 / 等待相机画面 / 点开全屏取景 · 机型）+ 右侧全屏图标。
- 全屏取景 `FullscreenPreview` 从 `ControlScreen` 内挪到 **`DoukRoot` 根层**渲染（`state.previewFullscreen` 分支），
  这样它盖住 Scaffold 的 bottomBar（douk 栏），才是真全屏；`BackHandler` 也放在根层，返回键先退全屏。
- 进全屏时用 `WindowCompat.getInsetsController(...).hide(systemBars())` 隐状态栏与导航栏，
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 允许上滑临时唤出，`onDispose` 时恢复。
- 全屏页底部补一颗 68dp 快门（`state.shoot()`），取景时不必退回参数页按快门。

### ② 拍摄按钮悬浮在 dock 栏上方

- `ShutterBar` 拆成 `FloatingShutter`：一条 `DoukShapes.pill` + `doukShadow(DoukElevation.raised)` 的悬浮胶囊，
  内含「张数计数 / 66dp 主快门 / AF（视频模式为 REC）」。
- 摆放方式：`ControlScreen` 主体改成 `Column { 顶栏; Box(weight 1f) { 滚动参数区; FloatingShutter(align BottomCenter) } }`，
  因为 `ControlScreen` 处在 `Scaffold` 的内容区（已带 innerPadding），`align(BottomCenter)` 天然落在 douk 栏上沿之上。
- 滚动区底部留 104dp 空白，滚到最后一行参数也不会被悬浮快门压住。

### ③ 「相机端显示已连接至智能设备，手机改参数没反应」——真因与界面处理

真因（来自 build107 真机日志 `session-20261116-211700.log` 的 `DESC_OK` 行）：连接与配对已真通
（`commands=130 restricted=false`、LiveView 出帧），卡点是**机身处于 AUTO/场景档**：

- 快门 `0x500D` 只上报 **1 个档位**且 `writable=false`
- 光圈 `0x5007` 只上报 **1 个档位**
- ISO `0x500F` 上报 28 档且可写 —— 因此只有 ISO 写入成功（日志 `ISO applied: ISO 6400`）

也就是说：不是 App 没发指令，而是相机把快门/光圈锁成了「单值只读」，下发必然被 `0x200F` 拒。

处理（build108–113）：

1. `NikonViewModel` 在 `syncCameraPropOptions()` 里把 `desc.writable` 与档位数存成状态：
   `shutterWritable` / `apertureWritable` / `isoWritable`，并记录 `exposureProgram`（`0x500E` 当前码）。
   `autoExposureLocked` = 曝光模式码不在 `1/3/4/5`（M/A/S/P）。
2. `DoukState.paramLockReason(key)`：可写返回 null，不可写返回一句人话原因（未连接时不挂锁标）：
   - 快门：`机身在自动／场景档，快门由相机决定；把机顶拨盘转到 M 或 S 再试`
   - 光圈：`机身在自动／场景档，光圈由相机决定；把机顶拨盘转到 M 或 A 再试`
   - 只读单值：`相机把快门上报为只读（只给 1 个档位）`
   - `vr` / `tone` / `pictureControl`：`该项由机身菜单控制，PTP 无写入通道`
3. 控制页表现：不可写的行右侧值置灰、行首挂一个「机身」小胶囊；点开对话框第一条就是原因，
   选项列表仍然照实列出（让用户看到机身当前值）但点不动（`clickable(enabled = false)`）。
4. `setParam` 先查 `paramLockReason`，被锁时直接给 `lastHint`，不再白发一次必败的 PTP 指令。
5. 曝光区顶部加一条 `exposureLockHint` 横幅：机身不在 M/A/S/P 时提示「把机顶模式拨盘转到 M 或 S，
   回控制页下拉刷新即可远程调」。
6. `NikonViewModel.lastParamMessage`：快门/光圈/ISO 下发后写入真实结果（`快门已写入 1/250s` 或
   相机返回的失败文案 `⚠️ 快门速度 下发被拒：请把相机曝光模式拨到 M / S / A 档…`），
   控制页显示「上次操作：…」，用户能立刻分辨「发了没生效」还是「根本没发出去」。

### ④ 「连接相机后设备那个按钮按不了」

`DeviceScreen` 已连接分支此前只有「进入控制面板」+「断开连接」，未连接分支的「添加设备」按钮（还受
`DoukPrefs.showAddDeviceButton` 控制）在连接后整块消失，所以连接后再也点不到「添加设备」。
build109 起已连接分支补一个 `TonalButton("添加设备")`，与「断开连接」并列，连接态随时可以换/再加一台相机；
顶栏的 `+`「添加设备」图标本来就常驻可点。

### ⑤ 精简连接提示

- `AddDeviceScreen.ConnectionHint` 由四段长文案压成三句：
  模式说明（`mode.detail`）＋「相机端：三代机清单 选「连接至智能设备」；二代机清单 选「连接到计算机（照相机控制）」」
  ＋「相机只允许一台设备连接，机身里其它已配对设备（含 SnapBridge）先删掉再连」。
  删掉了 `NikonModelSpec.steps(spec, mode)` 那段详细步骤与另一条多设备告警。
- `DoukState` 里配对前探测失败的提示也压缩：`$target 不可达。确认相机「连接至智能设备」已连上同一热点、
  热点未开「客户端隔离」，再点「搜索相机设备」重扫。`（详细诊断仍在日志文件里，界面不再铺开三段式排比）

### ⑥ 未连接不再给假参数

`DoukState.valueOf` 未连接时统一返回 `—`（此前会落到 `vm.iso/shutterSpeed/aperture` 的演示默认值
ISO 400 / 1/250s / F2.8），控制页警告条文案同时改为「相机未连接，参数区暂不可用」。

### ⑦ 本轮验证（build108–113，真机 87159031）

- 108 BUILD SUCCESSFUL → 控制页截图 `_tmp/c2.png`：监视按钮 + 悬浮快门就位
- 109/110/111 BUILD SUCCESSFUL → `_tmp/c3b.png`（去掉未连接时的满屏「机身」锁标）、
  `_tmp/c6b.png`（全屏已盖住 douk 栏）、`_tmp/c8b.png`（连系统栏一起隐藏）
- 112 BUILD SUCCESSFUL → `_tmp/e3.png`（添加设备页提示三句版）、`_tmp/g1.png`（未连接参数全为 `—`）
- **未能实机复验**：本轮相机 Wi-Fi 处于休眠/断开（`ping 192.168.125.174` 100% 丢包，`wlan1` 仍是
  192.168.125.60/24），因此「机身拨到 M 档后快门/光圈可写」这一步只有代码与日志依据，需相机重新开机联网后再测一次。

## 16.8 build114–117：取景按需启动、图库枚举修复、监视翻正、提示清理

### ① 取景不再「连上就开」（相机事务让出来）

`NikonPtpClient` 连接成功分支里删掉了 `startLiveView()`；`NikonViewModel` 新增 `startPreview()` /
`stopPreview()`，`DoukRoot` 用 `LaunchedEffect(state.previewFullscreen)` 在打开全屏监视时开、退出时停
（`stopLiveView()` 会发 `0x9202` 把控制权还给机身）。

真机依据：`session-20261116-214605.log` 里连上后立刻 `LiveView START cmd=0x9201` → 随后
`DESC_OK`、图标库/写属性全部被回 `0x2019 DeviceBusy` / `0xA002` / `0x201F`。取景本身就是一个常驻事务，
相机在取景期间不接别的命令，这是「连上什么都控制不了、图库也读不出来」的共同主因。

### ② 机身图库枚举：先取 storageID，再逐存储取句柄

`fetchCameraPhotos` 原来是 `GetObjectHandles(0x1007)` 一把梭，参数写 `(-1, 0, 0)`。改为：

1. `0x1004 GetStorageIDs` 拿存储 ID（失败只记日志，不阻断）；
2. 对每个 storage 发 `0x1007(sid, 0, 0xFFFFFFFF)`，第一个句柄参数用 `-1`（全部）；
3. 返回数据用统一解析器 `parseU32Array`（首项是数量）取出全部句柄，再倒序取区间；
4. 遇到 `0x2019 / 0xA002 / 0x201F` 先 `stopForStorage` 再等 400ms 重试一次。

新增 `storageBusy` 开关：读图库期间暂停每 2 秒一次的 `0x90C7` 新照片轮询，避免枚举事务被抢。
同时把机身公布的 130 个操作码整份落到会话日志（`NikonPtp/OPERATIONS`），排查时直接对表。

### ③ 监视画面翻正

`ControlScreen.FullscreenPreview` 的取景 `Image` 加 `Modifier.graphicsLayer(rotationZ = 180f)`：
尼康 PTP 送来的 JPEG 是机身方向，手机竖屏看着是倒的。

### ④ 连接失败提示整体删掉

- `DeviceScreen` 未连接分支不再渲染 `ConnectionHint(state)`（原本会把 `lastHint` + `connectionStage` 铺成小字）；
- `AddDeviceScreen` 手动填 IP 下方只在「正在连接」时回显一句进度，失败原因一律不落界面；
- `DoukState.searchDevices` 失败不再拼「本机网段没有发现 15740 端口…填任何 IP 都连不上」长句，
  扫描结果只进「搜索到的设备」列表；
- `connectSafely` 的不可达文案压成一句 `$target 不可达。`。

### ⑤ 连接过的设备就地列出 + 搜到即自动连

- `DeviceScreen` 未连接分支新增「连接过的设备」（读 `DoukPrefs.knownDevices()`）与
  「搜索到的设备」（读 `DoukState.lanFound`）两组列表，行内是机型/地址，点整行直接 `connectDirect`；
- `searchDevices` 扫描命中后比对 `knownDevices()`，命中已连接过的 IP 就立刻 `connectSafely` 自动连上，
  日志记 `Scan/AUTO_CONNECT ip=…`。

### ⑥ 本轮真机验证（build114/115，相机在线时段 22:00–22:05）

- `session-20261116-220032.log`：STA 连上后**没有**再出现 `LiveView START`（取景确实改为按需）；
- 机身拨到 M 档后 `DESC_OK 0x500D values=54 writable=true`、`0x5007 18 档 writable=true`、
  `0x500F 28 档 writable=true`，界面点选后日志 `Shutter speed applied: 1/250s`、`1/100s` —— 快门真写进机身；
- 图库仍报 `0x201F`（事务取消），因此 build116 加了 storageID 路径、`storageBusy` 与操作码落盘，
  build117 已构建装机，**这部分改进尚待相机联网后复验**；
- `_tmp/d1.png`（21:xx 未连接态）：设备页只剩「添加设备 + 连接过的设备（Z50_2 · V1.11 / 192.168.125.174）+ 支持的连接方式」，
  失败提示已完全不显示。

### ⑦ 待复验 / 未完成

- 相机在 22:05 后再次离线（`ping 192.168.125.174` 100% 丢包、`ip neigh` 显示 FAILED），
  图库枚举与监视 180° 翻转都还需要一次真机确认；
- `0xA002` = `PTP_RC_NIKON_OutOfFocus`（尼康私有码，来源 `gphoto/libgphoto2` 的 `camlibs/ptp2/ptp.h`），
  `0x201F` = `PTP_RC_TransactionCanceled`，两者都说明事务被打断，不是「相机里没照片」。


## 16.9 build119–build121：监视方向、更多菜单、监视中控机、属性兜底、搜索交互

### 1. 监视画面方向改为 -90°
`ControlScreen.kt` 里 `FullscreenPreview` 的取景 `Image` 由 `graphicsLayer(rotationZ = 180f)` 改为 `rotationZ = -90f`（尼康 PTP 取回的 JPEG 是机身方向，手机竖屏下 180° 是反的）。

### 2. 顶栏「更多」菜单（视频｜拍摄 右侧 ⋮）
`ControlScreen` 顶栏 `ModeSwitch` 之后新增 `IconButton(Icons.Rounded.MoreVert, contentDescription="更多")`，点开 `MoreActionsDialog`，四项全部接真实动作：

| 菜单项 | 真实实现 |
|---|---|
| 应用接管相机 | `NikonPtpClient.takeOverHostControl()`：`0x9435(1)` → `enterHostControl`（`0x90C2(1)` + `0xD1A6=0`），日志 `NikonPtp/TAKE_OVER mode=0x....`，判定 `0x2001/0x2005` 为成功 |
| 发送 GPS 位置至相机 | `NikonBleGpsClient.pushLocationNow()`：取 `lastLocation`（没有则 `runLastKnownLocation()` 读 GPS/NETWORK 最后一次定位）→ `NikonGeoPayload.build` 写 `NikonBleUuids.GEO` + `NikonTimePayload` 写 `TIME`；`DoukState.sendGpsToCamera()` 调它并落 `Gps/PUSH_ONCE` 日志 |
| 格式化储存卡（卡槽一/卡槽二） | `NikonPtpClient.listStorageIds()`（`0x1004` → `parseU32Array().drop(1)`）拿机身存储 ID，`formatStorage(id)` 发 `0x10F4`，整体包在 `withFramesPaused`，日志 `Storage/FORMAT sid=... code=0x....`；VM `formatStorage(slot)` 按响应码回中文结论（`0x2001` 已下发 / `0x2019` 忙 / `-1` 未发出 / 其他 `0x%04X`） |
| 版权与 IPTC 载入相机 | `CopyrightDialog` 收作者/版权 → `NikonViewModel.uploadCopyright()`，逐候选探测 `0xD303/0xD30E/0xD209` 的 `DevicePropDesc`，只写 `writable=true` 的，值用 `ptpString`（1 字节字符数 + UTF-16LE），日志 `Prop/COPYRIGHT_WRITE`；一个都没写成就如实回「机身未开放版权属性」 |

对话框 composable 都在 `ControlScreen.kt` 文件尾：`MoreActionsDialog` / `MoreRow` / `FormatStorageDialog` / `SpotButton` / `CopyrightDialog`。

### 3. 监视中也能控机（监视期间写不进去）
尼康在取景事务里写属性会被回 `0x2019`（DeviceBusy），因此：

- `NikonPtpClient.withFramesPaused(block)`：仅当 `_isLiveViewActive` 为真时 `pauseFrames()` + `0x9202`，`finally` 里 `startLiveView()` 恢复监视；
- `setPropRawValue`（快门/光圈/ISO/对焦/白平衡/驱动全部走它）整体包进 `withFramesPaused`；
- `control()`（拍照 `0x9207`、AF `0x90C1`、录像 `0x920A/0x920B`）也先暂停取景再发命令，成功/失败两条路径都恢复取景。

### 4. 「对焦模式 / 画质」显示「相机未上报」的定位与兜底
- 原因一：`refreshBodyStatus()` 里 `refreshExtendedProps()` 原先放在**函数末尾**，而前面的 `readStorageCapacity()`（`0x1004`+`0x1005`）在相机忙时会长时间占着 PTP 互斥锁，导致六个属性的 `DevicePropDesc` 一条都没发出去（旧日志里只有 `0x500D/0x5007/0x500F` 三条 `DESC_OK`，`0x500A/0x500B/0x5005/0x5013` 一次都没有）。现在 `refreshExtendedProps()` 提到 IO 协程**最前面**执行。
- 原因二：机身可能只回值不回描述。新增 `descOrNull()` / `rawValueOrNull()`：描述为 null 时改用 `getPropRawValue(0x0004 UINT16)`，失败再试 `0x0006 UINT32`，读到就按已有解码函数显示真实档位，并落 `Prop/VALUE_FALLBACK` 日志。
- 「画质」：`refreshExtendedProps()` 里新增对 `0x5003`（ImageSize）、`0x5004`（CompressionSetting）的 `DevicePropDesc` 探测，结果直接进日志。**这两个码在 Z50II 上到底报不报尚未确证**，所以本版没有新增「画质」参数行——不拿未验证的属性码做假按钮。

### 5. 设备页搜索交互（连接过的设备只在搜索时可见 / 搜不到回到原界面）
`DeviceScreen`：
- `LaunchedEffect(state.lanScanning, isConnecting, isConnected)` 结束时除了 `refreshing=false`，现在同时把 `pull=0f`，修掉「松开搜索相机」残留；
- 新增 `searchStartedAt` / `searchSeconds` 与 `LaunchedEffect(refreshing)`，每秒累加；`PullIndicator(pull, threshold, refreshing, seconds)` 刷新态文案为「正在搜索相机… Ns」；
- 「连接过的设备」改为条件渲染：`val searching = refreshing || state.lanScanning`，只有 `searching || state.lanFound.isNotEmpty()` 时才读 `DoukPrefs.knownDevices()` 并展示，否则整块隐藏（未连接态首屏回到「添加设备 / 支持的连接方式」）。

### 6. 本版验证证据（build120/121）
- `_tmp/build120.log` 首次编译在 `ControlScreen.kt:86 Unresolved reference: LocalContext` 失败，补 `import androidx.compose.ui.platform.LocalContext` 后通过；随后清掉未使用的 `context` 局部变量，`_tmp/build121.log` **BUILD SUCCESSFUL in 18s**（仅剩 `onBack`、`state` 两个未使用参数警告）。
- 装机（`87159031`）后 uidump 实测：控制页顶栏出现 `desc='更多' center=(1720,140)`；点开得到「更多操作」四项 + 关闭按钮；点「格式化储存卡」得到「卡槽一 / 卡槽二 / 取消」确认框。
- 设备页下拉与顶栏刷新都实测过：日志 `Scan/NOT_FOUND mode=WIFI_STA nets=wlan1 192.168.125.x；wlan0 10.98.15.x`（22:26:47、22:27:14 两次），4 秒后指示条消失、无「松开搜索相机」残留，列表回到未连接首屏。
- **未完成真机复验**：相机不在线（`10.98.15.174` ping 100% 丢包，`192.168.125.174:15740` `PRECHECK_PROBE open=false`，日志 `Connect/PREFLIGHT_FAIL`）。因此下面这些只有代码与编译级确认，没有相机侧回执：`-90°` 监视方向、监视中写属性/拍照、`0x10F4` 格式化、`0xD303/0xD30E/0xD209` 版权写入、`0x5003/0x5004` 画质探测、`0x1004→0x1007→0x1008` 图库枚举。


## 17.0 build122–build124：更多菜单就地展开、接管开关、版权独立页、监视退出还权、搜索命中才显示

### 1. 「更多」改成图标下就地展开
`ControlScreen` 顶栏把原来的 `IconButton + AlertDialog` 换成 `Box { IconButton(⋮) + DropdownMenu }`：
- 菜单宽 268dp、`DoukShapes.card` 圆角、`DoukColors.surface` 底色，锚在 ⋮ 正下方，不再整屏弹窗；
- 第一行「应用接管相机」用 `Switch`（`state.hostControlTaken` → `state.setHostControl(on)`），关掉即发 `0x9435(0)` + `0x90C2(0)` + `0xD1A6=1` 把控制权还给机身；
- 第二行「发送 GPS 位置至相机」（`state.sendGpsToCamera()`）；
- 第三行「版权与 IPTC」（进入独立设置页）；
- **格式化储存卡整项删除**（含 `FormatStorageDialog` / `SpotButton`，以及 `NikonPtpClient.formatStorage` / `listStorageIds`、`NikonViewModel.formatStorage`）。

### 2. 版权与 IPTC 独立设置页
新增 `ui/douk/CopyrightScreen.kt`，`DoukState.showCopyrightSheet` 为入口，`DoukRoot` 在 `previewFullscreen` 之后分支渲染（`BackHandler` 返回）。
- 「从相机导入 IPTC 数据」→ `NikonViewModel.readCopyrightFromCamera()`：逐候选 `0xD303/0xD30E/0xD209` 调 `NikonPtpClient.readPropAsString()`（`0x1015` 取原始包，按 PTP 字符串 1 字节字符数 + UTF-16LE 解码），读到就填进输入框，日志 `Prop/COPYRIGHT_READ`；
- 「写入相机」→ 仍走 `uploadCopyright()`，`SetDevicePropValue` 写回，日志 `Prop/COPYRIGHT_WRITE`；
- 页面顶部带 `statusBarsPadding()`，标题与返回键和设置子页一致。

### 3. 监视退出把控制权还给机身
`stopLiveView()` 在 `0x9202` 之后补调 `leaveHostControl(c)`（`0x9202 → 0x9435(0) → 0x90C2(0) → 0xD1A6=1`）。此前只停帧不还权，机身会一直停在「被应用接管」状态，机身按键/拨盘都不能用。
同时 `withFramesPaused` 与 `control()` 在停帧后补一次 `ready(c)`（`0x90C7` 轮询到 `0x2001/0x2005` 才继续），并把 `setPropRawValue` 改为**最多两次尝试**（第一次失败且响应码为 `0x2019` 时等 120ms 再写），每次写都落 `Prop/SET_RAW` 日志（属性码、原始值、结果、响应码）。

### 4. 监视画面铺满不变形
全屏取景 `contentScale` 由 `ContentScale.Fit` 改为 `ContentScale.Crop`：整屏填满、只裁两侧，不改比例。

### 5. 搜索：只有真搜到这台连接过的设备才显示
`DeviceScreen` 的「连接过的设备」由「有历史就全列」改为：
```kotlin
val knownDevices = if (searching || state.lanFound.isNotEmpty())
    DoukPrefs.knownDevices().filter { known -> state.lanFound.any { it.ip == known.ip } }
else emptyList()
```
即只有本轮扫描结果里出现同一 IP，才显示这条历史设备；没搜到就整块不出现，界面回到原样。

### 6. 对焦/画质属性的 GitHub 查证结论
在 `gphoto/libgphoto2` 的 `camlibs/ptp2/ptp.h`（master）确认：
- `PTP_DPC_FocusMode 0x500A` 是标准 PTP 属性；尼康另有 `PTP_DPC_NIKON_AutofocusMode 0xD161`；
- 尼康私有画质属性为 `PTP_DPC_NIKON_1_ImageCompression 0xF009`、`PTP_DPC_NIKON_1_ImageSize 0xF00A`；
- `0x9202` 只出现在 `ptp.h` 的操作码定义处，没有额外语义注释。
据此本版 `refreshExtendedProps()`：
- 对焦模式读取顺序改为 `0x500A desc → 0xD161 desc → 0x500A 读值 → 0xD161 读值`，任一命中就显示真实档位；
- 画质探测扩到 `0x5003 / 0x5004 / 0xF009 / 0xF00A` 四个码，全部落日志；**仍不新增「画质」参数行**，因为 Z50II 到底报哪个码还没有日志证实。

### 7. 验证证据（build122–124）
- `_tmp/build122.log`、`build123.log`、`build124.log` 均 BUILD SUCCESSFUL（123：13s，124：10s）。
- 装机实测（`87159031`）：控制页 ⋮ 点开，菜单出现在图标正下方 `y≈267`，三行文案 + 交接开关；开关节点 `checkable=true` 坐标 `[1600,230][1730,350]`，点一次 `checked=true`，再点 `checked=false`。
- 点「版权与 IPTC」进入独立页：标题、说明、「从相机导入 IPTC 数据」、「作者 / 摄影师」、「版权声明」、「写入相机」六个节点齐全，返回键 `desc='返回'`。
- **未真机复验**：相机仍未上线（`10.98.15.174` ping 全丢、`192.168.125.174:15740` `PRECHECK_PROBE open=false`）。因此 -90° 方向、Crop 铺满效果、监视中写属性/拍照、退出监视还权、`0xD161` 对焦兜底、版权读写、GPS 推送都只有编译级确认。

## 18.0 build143–146：图库「更多 / 原图 / 时间分组」+ 设备页数据与参数写入修复

### 1. 图库右上角改为「更多」，筛选并入
- `GalleryScreen.kt` 顶栏右侧的筛选 `IconButton` 换成 `Icons.Rounded.MoreVert`，用 `DropdownMenu` 展开两项：
  - 「筛选（已启用）」→ 打开筛选整屏页；有筛选条件时图标与首项都变主题色；
  - 「始终显示原图」→ 勾选态存在 `DoukPrefs.galleryShowOriginal`（`douk_settings.xml: gallery_show_original`），下次进图库仍生效。
- 筛选从 `Dialog` 改成整屏 `GalleryFilterPage`：返回箭头 + 标题 + 三组胶囊（类型 / 格式 / 构图）+ 重置 / 应用，带 `BackHandler`，符合「设置类不弹窗」的既有约束。
- 看图页「更多」窄卡新增「显示原图」：仅相机内、非视频、当前还没取到原图时出现；点击后走 `DoukState.cameraOriginalBitmap(handle)` → `NikonPtpClient.downloadCameraPhoto()`（0x1009），按最长边 2560 采样解码防 OOM。总开关打开时，打开任意机身照片直接走同一条原图链路。

### 2. 「照片 / 相册」浮层
- `GalleryViewSwitch` 外框与选中块的圆角都改成 `RoundedCornerShape(percent = 50)`（真正的胶囊），宽度仍 232dp。
- dock 的「图库」第二次点击改为 toggle：`showViewSwitch = !showViewSwitch`，再点一次收起。

### 3. 机身照片按真实拍摄时间分组
- `NikonPtpClient.fetchCameraPhotos()` 在 `0x1008` 的 ObjectInfo 里，文件名之后继续读 PTP 字符串（1 字节长度 + UTF-16LE）得到 `CaptureDate`（`yyyyMMdd'T'HHmmss`），新增 `parsePtpStringTime()` 转毫秒；解不出才回落 `System.currentTimeMillis()`，并打 `CAPTURE_DATE_MISS` 日志（本次 200 张 MISS=0）。
- `CameraGallery` 把 `dateModified` 写进 `GalleryItem.dateMillis` 并按时间倒序排序；`PhotoGrid` 的日期头从「机身照片 200 张」改成「10月25日 ｜ 机身」这种「日期 ＋ 竖线 ＋ 来源」，只有全部没有时间时才回落数量标题。

### 4. 设备页「未上报」的真正根因（已修）
`NikonViewModel.onConnected()` 里 `refreshBodyStatus()` 原先在 `isConnected = true` **之前**调用，而该函数第一行就是 `if (!isConnected) { 清空并 return }`，所以电量 / 存储 / 镜头永远是「未上报」，`refreshExtendedProps()` 也从未执行（日志里只有 3 条 0x500x 描述）。
- 修法：把 `refreshBodyStatus()` 移进 `viewModelScope.launch(Dispatchers.Main) { isConnected = true … }` 块内。
- 真机结果（Z50II / build145）：电池 `35%`、存储 `112.5 GB / 119.3 GB`（`0x1005` 真实容量）、镜头「当前焦距 25mm」（`0x5008`），曝光模式 `S 快门优先`、测光 `点测光`、白平衡 `自动`、驱动 `连拍（低速）` 全部显示。
- 参数写入同时得到验证：ISO 500→800 提示「ISO 已写入 ISO 800」；快门 1/100→1/500 提示「快门已写入 1/500s」；曝光模式 S→M 成功（都走 `setPropRawValue`：`0x90C0 DeviceReady` + `0x1016`，写前 `withFramesPaused` 让出取景通道）。
- 光圈 `0x5007` 仍 `writable=false`：相机把光圈交给镜头光圈环/机身，不是应用缺陷；提示文案改成「光圈由镜头光圈环/机身控制（相机上报为只读），快门与 ISO 仍可远程调。」，不再笼统说「快门或光圈只读」。

### 5. 连接速度
- `CameraLanProbe.scan()` 新增 `preferred` 参数：先用上次连上的 IP 单点探测，命中立即返回，不再扫 254 个地址；并发从 48 提到 64，并且命中第一台就停止后续批次。
- 自动连接循环、手动搜索、配对换 IP 重扫都传入 `DoukPrefs.knownDevices()` 的 IP。
- BLE：`onConnectionStateChange` 里补 `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`；NOT1 兜底 3s→1.5s、控制器名无回调兜底 3s→1.2s。
- 真机实测冷启动：13:05:45.540 `CONNECT_KNOWN` → 13:05:46.525 `连接成功`，约 1 秒；13:05:46.797 机身状态已回填。

### 6. 尚未验证
- 蓝牙侧只改了握手节奏，相机当前不在 BLE 广播态，无法测实际耗时；需要相机进入「网络 → 连接至智能设备」的等待界面再复测。
## 18.1 build147–148：机身相册按真实目录、RAW+JPG 合并、省电读取、白色「更多」卡片

### 1. 机身相册只识别一个文件夹（已修）
- 根因：枚举 BFS 有 `inspected < 400` 上限、目录深度卡在 3 层，而且 `CameraStorageFile` 没有目录字段，`AlbumGridPanel` 的 CAMERA 分支直接返回一条写死的「机身存储」。
- 修法：`NikonPtpClient.fetchCameraPhotos()` 的队列改成 `Triple(handle, depth, folderName)`，进目录时先读该文件夹 0x1008 里的 PTP 字符串（`100NZ502` / `101NZ502`…）并带进每个子项；上限放宽到 4000 个对象、6 层目录；`CameraStorageFile` 新增 `folder`，相机图库的 `album` 用它。
- 真机结果：`ENUM_DONE folders=483 photos=477`（此前 394 且只显示 200），相册页出现 `102NZ502 251 张 / 101NZ502 163 张 / 103NZ502 40 张 / 104NZ502 22 张`；点进某个目录后列表只剩该目录的照片。

### 2. RAW+JPG / HEIF 只显示一张
- 新增 `mergePairedFormats()`：按「目录 + 去掉扩展名的主干名」合并，主图优先 JPG/HEIF，尺寸相加、拍摄时间取较新，条目里保留全部格式（`formats`）。
- 筛选改为命中任意一种格式（选 RAW 时 RAW+JPG 条目也在）。
- 缩略图左下角统一加格式角标：`JPG`、`RAW+JPG`、`MOV` 等；去掉原来左上角单独的 RAW 标记。
- 真机截图：同一张 `LYS_3298.JPG` 只剩一格，左下角显示 `RAW+JPG`。

### 3. 读图省电
- 机身列表加会话级缓存：首次枚举后按 `sessionVersion + 15 分钟` 复用，进图库不再把 400+ 条 `0x1008` 重读一遍；断开连接即失效。日志实测第二次进入 `CACHE_HIT photos=477 ageMs=56089`，0.08 秒返回。
- 网格缩略图改成先取 `0x100A` 小图（几十 KB），拿不到才回落 `0x90C4` 1600×1200 大图；解码不再二次降采样。避免 200 格栅格拉几百 MB 的 PTP 流量。
- 图库关闭时不再保留取景（此前已做），列表不在前台不轮询。

### 4. 「更多」卡片与筛选卡片
- 不再用 Material `DropdownMenu`（无法指定容器色），改成根 `Box` 内自绘浮层：整屏透明点击层负责「点空白收起」，右上层叠两张 `Surface` 白卡，圆角 28dp、投影 16dp。
- 「筛选」卡片出现在「更多」卡片左侧（宽 300dp，类型 / 格式 / 构图胶囊 + 重置 / 应用），不再整屏跳页；格式随类型联动（图片只给 JPG/RAW/PNG/HEIC）。
- 文字固定深色、选中态固定绿色（`#139B6A`），不跟 App 主题色走；「始终显示原图」只有打开时右侧才出现对勾。
## 18.2 build149–150：看图页「保存到」三个出口真正可用

- 旧实现只处理「应用内 / 手机相册」两种来源，相机内的照片（origin=cam / usb）直接返回「要先回传」，三个按钮点了都失败。
- 现在 `AddToSheet` 先通过 `readFiles` 取原文件字节：
  - 相机内：`DoukState.cameraPhotoBytes(handle)` → `NikonPtpClient.downloadCameraPhoto`（0x1009）拉完整文件，不解码不缩图；
  - USB：`DoukState.usbPhotoBytes(context, handle)` → `copyObjectToFile` 落临时文件再读；
  - 应用内：直接读下载目录里的文件；手机相册：按 content uri 读流。
- 三个出口：
  1. 应用相册 —— 写入 `Pictures/Douk/<机身目录名>`（如 105NZ502），MediaStore + IS_PENDING 落盘，图库「应用内」按目录分组可见；
  2. 手机相册 —— 写入 `DCIM/LensLink`，系统相册可见；
  3. 自定义文件夹 —— `ActivityResultContracts.OpenDocumentTree` 选目录，持久化读写授权后用 `DocumentsContract.createDocument` 写入，重名自动加序号。
- RAW+JPG 合并条目补了 `extraHandles`：保存时把配对的 NEF 一起写出去。真机验证 `LYS_3298` 同时落 `DCIM/LensLink/LYS_3298.JPG` + `.NEF`（9.3MB + 16.2MB），选 Pictures 作自定义目录同样两份都落盘。
- 删除/复制仍沿用原逻辑；界面文案改成「保存到」，说明改成「三处都保存原文件；相机里的照片会先从机身下载」。
## 18.3 build151：竖拍照片按 EXIF 转正、去掉构图筛选

- 竖拍照片横着显示的原因：机身 JPEG 是「像素仍横放 + EXIF Orientation=6/8」，原来 `BitmapFactory` 解码后直接显示，没有按 EXIF 旋转。
- 修法：`GalleryFileOps` 新增 `exifOrientationOf(bytes/file)` 与 `applyExifOrientation(bitmap, orientation)`，覆盖 2/3/4/5/6/7/8 全部方向；四处解码统一套用：
  - `DoukState.decodeThumb`（机身网格小图）；
  - `DoukState.cameraOriginalBitmap`（看图页「显示原图」）；
  - `GalleryScreen` 的 `decodeFileThumb`（应用内文件网格）；
  - `GalleryViewer.loadLargeBitmap` 的应用内大图分支。
- 真机验证：竖拍的 LYS_3298 打开后是正常竖幅（教堂尖顶朝上），不再横躺。
- 筛选卡片和整屏筛选页都删掉了「构图：全部 / 横构图 / 竖构图」这一组，现在只剩类型（全部/图片/视频）和格式（全部/JPG/RAW/PNG/HEIC/MP4/MOV），格式仍随类型联动。
## 18.4 build152–156：下载格式 / 来源分级 / 连接横跳 / 智能设备模式 GPS

- build152–153：设置-下载新增「从相机下载的格式」（`DoukPrefs.saveFormat`，默认 `JPG + RAW`）；`AddToSheet` 按来源分级——相机内三行（应用相册 / 手机相册 / 自定义文件夹，按格式过滤，RAW+JPG 配对文件一起落），手机内两行（复制到应用相册 / 移动到应用内），应用内两行（保存到手机相册 / 移动到应用相册）；`scanAppImages()` 改 `walkTopDown()` 递归，`Pictures/Douk` 子目录里的文件也能在「应用内」看到。
- build154 连接横跳根因修复：`AUTO_LOOP_CONNECT` 每 25–30 秒重连一次，而每次 `startNikonWiWatch()` 开头的 `stopNikonWatch()` 会先自增 `generation` 再同步 close 旧 socket；旧 `listenEvents` 线程读到 EOF 时仍满足 `connection === c`，把「主动关闭」误报成「事件通道中断」→ `transportFailed` → `onDisconnected` → UI 闪「已断开」，新会话握手成功又「连接成功」，形成横跳。
  - `listenEvents(c, token)` 带上本次会话 `generation`；catch 里用 `connection === c && generation == token` 判断是否仍是当前会话，非当前会话只记 `EVENT_CLOSED_SUPERSEDED` 直接返回。
  - 事件通道 EOF 不再直接判死：先用一次 `0x1001 GetDeviceInfo`（2.5s 超时、持 `c.mutex`）探命令通道，仍在线则只记 `EVENT_CHANNEL_LOST` 并降级为轮询，不触发 `onDisconnected`；命令通道也失败才 `transportFailed`。
  - `NikonPtpClient.hasConnection` 暴露真实会话状态；自动重连循环除 `isConnected/isConnecting` 外再判 `hasConnection`，事件抖动时不会顶掉活着的会话。
  - `NikonViewModel.connect()` 对「同一 IP + 已连接 + 会话仍在」直接跳过重复握手，避免自己关掉自己。
  - 真机 87159031 / Z50_2 / Wi-Fi STA 连续观察 3 分钟：只有启动时一条「连接成功」，无 `AUTO_LOOP_CONNECT`、无 `已断开`。
- build155–156 智能设备模式 GPS：尼康「连接至智能设备」的定位只能走蓝牙（SnapBridge / nokkin 同一路径，PTP 没有公开 GPS 注入操作码，libgphoto2 的 Nikon 扩展里也只有曝光/取景类）。`sendGpsToCamera()` 现在三级兜底：直接推 → `reconnectSaved()` 直连上次 BLE 地址再推 → `startScan(autoConnect=true)` 扫已配对相机、等最多 10 秒 READY 后再推；失败文案区分 STA 模式，明确提示相机蓝牙要开着。
  - Wi-Fi STA 连上时 `ble.autoConnectIfNeeded(force = true)` 主动扫一轮相机蓝牙；`autoConnectIfNeeded` 允许「只有保存地址、没有设备列表」时也扫描。BLE 连上后由 `NikonBleGpsClient` 按设置的间隔持续推送定位。
  - 实测限制：相机已经连上手机热点 Wi-Fi 时不再广播 BLE（扫描 12 秒零结果），所以 STA 下要推 GPS，必须先在相机「连接至智能设备 → 配对（蓝牙）」界面完成一次蓝牙连接并保持；这不是协议缺失，而是尼康机身的行为。

## 18.5 build157–161：图库编辑器换成 ImageToolbox 体系 + 设置「图像编辑」

- 目标仓库 T8RIN/ImageToolbox 是多模块工程（`feature/single-edit`、`core/filters`、`lib/curves`、`lib/cropper`，643MB/7640 文件，依赖自家 Gradle convention plugin、Koin、GPUImage、GMIC 原生库），不能整包拖进当前单模块 Compose 工程；因此按它的编辑体系与交互重建，算法按 Apache-2.0 重写，不复制模块。
- `PhotoEditor.kt`：分组由「基础/调节/滤镜」改为「裁剪 / 调节 / 曲线 / 滤镜」四组。
  - 裁剪：旋转 ±90°、水平/垂直翻转、±45° 微调滑杆、原始/1:1/3:4/4:3/9:16/16:9，新增「自由裁剪」——四角可拖动、框外压暗，归一化 cropL/T/R/B 存进 `EditState`，保存时 `cropByRect` 真实裁像素。
  - 调节：曝光/亮度/对比度/高光/阴影/饱和度/自然饱和度/色温/色调/锐化/清晰度/颗粒/降噪 + HSL 色相/饱和度/明度，沿用逐像素渲染。
  - 曲线：三点滑杆删除，改为五个固定横坐标（0/0.25/0.5/0.75/1）可上下拖的 Canvas 曲线，Catmull-Rom 插值，拖动时保证单调；内置线性/S 曲线/提亮/压暗/褪色五个预设；渲染时生成 0..255 LUT 查表。
  - 滤镜：6 个扩到 18 个（原图/鲜明/柔和/胶片/复古/黑白/高反差黑白/冷调/暖阳/青橙/森系/日落/夜景/通透/低饱和/奶油/港风/赛博），每个 `FilterLook` 带曝光、对比、饱和、色温、色调、褪色、黑白混合真实参数，`filterAmount` 0–100 按比例混回原图；滤镜宫格四列。
  - 画面：可开九宫格三分线；长按显示未编辑原图并标「原图」。
- `DoukPrefs.kt`：新增 `editorFormat`（JPG/PNG/WebP）、`editorQuality`（默认95，60–100）、`editorPreview`（默认1080，480–2048）、`editorGrid`、`editorCompare`。
- 保存：按设置选 `Bitmap.CompressFormat`、质量与扩展名（jpg/png/webp），输出到 `Pictures/Douk/<原名>_edit.N`，重名自动加序号；PNG 忽略质量，JPG/WebP 用设置质量。
- 设置-通用「资源」后新增「图像编辑」独立整屏二级页：导出格式、导出质量、预览分辨率、显示九宫格、按住对比原图；选项仍走 `ValuePick` 整屏选择页。
- 真机 87159031 验证：四组切换正常；曲线拖动后画面实时变亮；黑白滤镜与 0 强度回原图正常；自由裁剪四角可拖；设置页 PNG 选择后保存得到 4096×2730 `LYS_3327_edit.png`，无崩溃。

## 18.6 build162–164：底部主功能栏 + 人像美颜 + 滤镜缩略图

- 参考仓库：
  - `pixpark/gpupixel` 是 C++11 + OpenGL/ES 实时库（Skin Smoothing / Skin Whitening / Face Slimming / 大眼），原生模块不适合并入当前单模块 Compose 工程；按它的效果链用 Kotlin/CPU 重建磨皮、美白、红润、瘦脸，四项都真实作用到像素。
  - `k1monfared/imgedt`、`Hunam6/RapidRaw` 的 README 本轮 GitHub API 仍取不到，按市面上专业修图 App 的共识采用「底部主功能 + 上方子工具」排布，不臆造其内部实现。
- `PhotoEditor.kt` 布局：顶部还原栏 → 图片 → 当前主功能的子面板 → **底部 MainTabBar**（裁剪/调节/曲线/滤镜/人像，图标+文字，选中橙色）。原先位于图片下方的 tab 行删除，主功能真正落到最底部。
- 调节二级分组：`ADJUST_GROUPS` = 光效（曝光/亮度/对比度/高光/阴影）、色彩（饱和度/自然饱和度/色温/色调/HSL 三项）、细节（锐化/清晰度/颗粒/降噪）；组 chips 与子项 chips 都在主栏上方，避免 16 个滑杆平铺。
- 人像：`EditState.smooth/whiten/rosy/slimFace`。`detectFace()` 用 `android.media.FaceDetector` 在最长边 640 的 RGB_565 图上检测，取眼睛距最大的脸，归一化成 `FaceBox`；进页面检测一次，换图重测。
  - 磨皮：可分离盒式模糊 + 肤色蒙版，只混肤色像素；美白/红润同样只落肤色，避免背景漂白。
  - 瘦脸：只在 FaceBox 椭圆内做横向采样收缩，k=0..1；未检测到人脸时该 chip 禁用并提示，磨皮/美白/红润仍可用。
- 滤镜宫格改为**实时缩略图**：进页面按当前图生成 128px、18 个预设的小图（IO 线程），卡片显示缩略图 + 名称 + 选中圆点；滤镜强度滑杆保留。
- 真机 87159031 验证：
  - 教堂图：五组切换正常，底部主栏在屏幕最下；滤镜缩略图能区分原图/黑白/冷调；磨皮 67 有可见柔化；无人脸时瘦脸禁用并提示。
  - 人像图：识别到人脸，瘦脸 67 后脸型明显收窄；保存得到 `portrait_test_edit.png`，无 OOM/崩溃；此前保存 `LYS_3298_edit.png` 4096×2730 正常。

## 18.7 build166–170：删人像、单行滤镜、分类与尼康/3D LUT 导入

- **彻底删除人像**：`EditState` 去掉 `smooth/whiten/rosy/slimFace`；删除 `PORTRAIT`、`PortraitRow`、`FaceBox`、`detectFace()`、`applyPortrait()`、`boxBlur()` 与人脸肤色判断；`MainTabBar` 从五项缩为四项（裁剪/调节/曲线/滤镜），`render`/`renderPreview`/`renderFull`/`saveEdited` 全部不再接收人脸参数。文件内已无 `Face`/`人像`/`smooth` 残留。
- **滤镜单行横滑**：`FilterGrid` 由四列竖向宫格改为一行 `horizontalScroll`；卡片宽 62dp、高 76dp、缩略图高 58dp，名称压在图上，选中项右上角橙点。缩略图改为 `renderPreview(small, …, 128, runtime)`，每张独立复制源图，避免连续滤镜互相污染。
- **强度实时预览**：滤镜页顺序为「强度滑杆在上、缩略图在下」；滑杆拖动直接改 `filterAmount` 并触发预览，松手才入撤销栈。实测从 100 拖到 40/23，画面即时变化。
- **分类条**：顶部「还原」右侧在滤镜 tab 下显示 `自带` + 导入滤镜出现过的分类；点击分类即过滤缩略图。切到滤镜时若当前滤镜属于某分类，会自动定位。
- **滤镜分类设置**：`DoukPrefs.filterCategory(fileName)` / `setFilterCategory()`，SharedPreferences key 为 `filter_cat_<文件名>`，默认「未分类」；设置 → 图像编辑新增「滤镜分类」整屏页，按文件逐项点选 未分类/人像/风光/胶片/黑白/自定义，选中态即时重绘。内置 18 个固定「自带」。
- **资源导入格式**（`FilterResources.kt` 新建）：
  - `.cube`：`TITLE`/`DOMAIN_*` 注释行跳过，`LUT_3D_SIZE` 2..128，按 red 最快 `index=((b*N+g)*N+r)*3` 解析，三线性插值。
  - `.3dl`：跳过 `3DMESH`/`Mesh` 头，按行读取三元整数，立方根推断 N，按 blue 最快近似排列，按最大值 255/1023/4095 归一化。
  - `.look`：按 3D LUT 文本走 `.cube` 解析（XML 型 .look 不冒充支持）。
  - 尼康 `.NP3/.NCP/.NP2`：校验 magic `NCP`；对比度 272、高光 282、阴影 292、白 302、黑 312、饱和度 322，均按无符号字节 `-128`；还原成 33³ 等效 LUT。曲线块偏移在不同机型/注释块下不固定，本轮不硬读，避免把错位字节当曲线导致整图发黑。
  - 解析失败的文件不进入列表，不生成假按钮。
- **渲染接线**：`applyColor(px, state, runtime)` 在内置 look 之后，对 `res:` 滤镜查 3D LUT，按 `filterAmount` 与原像素线性混合；预览与全尺寸保存共用同一条链。
- **真机 87159031 验证（build170）**：
  - 编辑页只剩四主功能；滤镜页单行缩略图左右滑动，未分类/风光分类条正常。
  - 合成 `.cube`、`.3dl`、`.np3` 三个样本均出现在「未分类」并生成正确缩略图；`.np3` 全强度预览有对比度/饱和度变化；分类改到「风光」后该滤镜从「未分类」移入「风光」，重启后仍保持。
  - 保存全尺寸 4096×2730 PNG 成功，无 OOM；测试用合成滤镜与测试图已从设备删除。

## 18.8 build171–176：调节页卡片化、曲线/HSL 二级面板、裁切与滤镜缩略图重做

- **调节页（对齐参考图一）**：`ADJUST_GROUPS` 改为 光效（曝光/亮度/对比度/高光/阴影）、色彩（饱和度/自然饱和度/色温/色调/HSL/曲线）、细节（锐化/清晰度/颗粒/降噪）。分组名改为三等分居中文字，选中组黑色 SemiBold、其余灰色；功能卡 56dp×64dp、11dp 圆角、6dp 间距，每项配 `ImageVector` 图标（`WbSunny/Brightness6/Contrast/Highlight/Brightness3/Palette/ColorLens/Thermostat/Tune/AutoFixHigh/Details/Grain/BlurOn/Gradient/Timeline`），选中橙框 + 浅橙底；选中卡数值非 0 时用数值替换图标。
- **主滑杆 `AdjustSlider`**：自绘 78dp 高区域、20dp 灰色胶囊轨道、14dp 半径白芯 + 橙描边圆球；按下/拖动时圆球上方绘制 50dp×26dp 深色气泡显示 `+20 / -36`，松手才 `commit()` 入撤销栈；`awaitEachGesture + awaitFirstDown` 自行处理手势，`apply()` 放在手势内部避免 restricted suspend 限制。
- **曲线 `CurvePanel`**：从主 tab 移除，改由「色彩 → 曲线」进入；192dp 高、透明底、5×5 网格；白线 1.6dp + 深色投影 3.2dp；5 个 7dp 半径控制点（白芯灰圈）；底部主/R/G/B 四通道圆点（26dp）、重置、收起。`EditState` 新增 `curveR/curveG/curveB`，默认 `[0,0.25,0.5,0.75,1]`；`applyColor` 先做主曲线 LUT，再逐通道查 `redLut/greenLut/blueLut`。
- **HSL `HslPanel`（对齐参考图三）**：三条渐变滑杆（色相围绕当前色相 ±60° 循环、饱和度低→高、亮度暗→亮），23dp 轨道、11dp 圆球，标签在左、数值在右；底部 6 个色相圆点（0° #D64545、30° #E2864A、60° #E9D35A、120° #6FD45A、205° #5B87E0、280° #9A5BD6），选中加灰环。算法只作用于 `hslRange` 附近像素：按环形色相距离 `weight`、阈值 0.13，分别施色相偏移、饱和度倍率、明度偏移，避免整图偏色；实测选中绿色拖饱和度到 +73，草地绿色明显变浓、其它区域基本不动。
- **裁切**：自由裁剪框内侧绘制 3dp 白边 + 1.9 倍深色投影，四角 9dp 白色手柄 + 46% 橙点，贴边时也不会被裁掉；比例行改为 `CropOptionChip`（图标 + 文字胶囊，选中浅橙底橙字），`ratioIcon()`：1:1→`CropSquare`，3:4/9:16→`CropPortrait`，4:3/16:9→`CropLandscape`，原始→`Crop`，自由→`CropFree`。
- **滤镜缩略图**：`FilterGrid` 改为 64dp×78dp 全图卡片，12dp 圆角、黑底、缩略图 `fillMaxSize`；底部 44dp 黑色渐变遮罩，名字 10sp 白字直接压左下角，删除白色条；选中 1.5dp 橙框 + 右上橙点。缩略图仍用独立副本 + `renderPreview(..., runtime)` 避免滤镜叠加污染。
- **实时预览双通道**：`live()` 记录 `lastLiveAt`；`commit()` 递增 `previewEpoch`。`LaunchedEffect(state,item,filterRuntime,previewEpoch)` 中距上次 live <400ms 时延迟 55ms、用 `min(previewSide,900)` 快算；否则延迟 12ms、按设置 `previewSide` 高清补算。拖动跟手、松手补高清。
- **编译坑（已修）**：滤镜页临时 `AdjustItem` 少传 `icon`；`awaitEachGesture` 内不能直接调外部 suspend 本地函数；`Canvas` 绘制 lambda 内不能调用 `LocalDensity.current`（须在组合作用域提前捕获为 `val`）。
- **真机 87159031 验证（build174/175）**：调节页结构、三组居中文字、图标卡、选中橙框正常；拖动曝光到 +20 画面变亮，按住显示 -36 气泡；曲线面板白线、5 点、四通道、重置/收起正常，拖动中点后中间调变亮；HSL 面板三条渐变滑杆与六色点正常，选绿色后渐变条整体切到绿色，饱和度 +73 生效；自由裁剪框白边/橙角点清晰，比例胶囊图标正常；滤镜缩略图全部为全图效果、无白条、名字压图。

## 18.9 build177–178：全编辑器滑杆统一、GPS 推送设置可调

- **滑杆统一**：`AdjustSlider` 新增 `rangeOverride` 与 `formatValue`，`SliderRow`（裁剪页旋转、滤镜页滤镜强度）由 Material3 `Slider` 改为调用 `AdjustSlider`。旋转传 `-45f..45f` 并用 `°` 格式，滤镜强度传 `0f..100f` 并显示整数；顶部行统一「左标签 + 右橙色数值」，拖动气泡也显示同一个格式化值。移除 `Slider` / `SliderDefaults` 依赖。
- **GPS 设置页 `GpsPanel`**：从三开关扩为「附加位置 / 蓝牙推送 / 最近一次推送」三段。保留连接至智能设备、导入图片、蓝牙 GPS 三个开关；新增「推送间隔」1/2/3/5/10/20/30 分钟或 1 小时、「定位精度」高精度/均衡/低功耗、「同时校准相机时间」开关；最近推送直接读取 `douk_nikon_ble` 的 `last_push_at` / `last_push_place`，没有相机回执时显示「还没有成功推送」。所有选项都是整屏二级页，不弹窗。
- **配置真实生效**：间隔与精度写在 BLE 客户端自己的 `douk_nikon_ble` 里（键 `interval_min`、`location_accuracy`、`sync_time`）。`startGpsLoop()` 按精度决定 `requestLocationUpdates`：高精度 GPS 10s + 网络 60s；均衡 GPS 30s + 网络 120s；低功耗仅网络 60s。推送循环每轮重新读 `interval_min`，设置后下一轮生效；`sync_time=false` 时循环和 `pushLocationNow()` 都不再写 `TIME`。定位精度在下次连接时重新注册。
- **真机 87159031 验证（build177/178）**：旋转滑杆显示「旋转 0°」，拖动到 11° 图像实时倾斜，按住气泡显示 8°；滤镜强度滑杆显示 100，样式与调节页一致；GPS 页三段卡片、间隔选择页、精度选择页正常，选 10 分钟后返回显示「10 分钟」，选高精度后 `douk_nikon_ble.xml` 写入 `location_accuracy=high`。

## 18.10 build181–184：自由裁剪九宫格、去整圈边框、实时预览提速

- **自由裁剪框 `CropOverlay`**：删除原来贯穿裁剪区的 3dp 白边与 1.9 倍内侧深色描边，改为四角 L 形短臂（18dp × 2dp），白线 + 深色影子，亮图和暗图都能看清；不再画四角白圆/橙点。
- **拖动才出九宫格**：`corner >= 0`（`onDragStart` 到 `onDragEnd/onDragCancel`）时才在裁剪区内画两条竖线、两条横线（白色 38% 透明，1px）；松手或取消立即消失，未调节时只有四角角标和外部暗化。
- **裁剪预览不再二次裁切**：裁剪 tab 且 `cropFree` 时，传给渲染的 `renderState` 把 `cropFree=false`、`cropRatio="原始"`、`cropL/T=0`、`cropR/B=1`，编辑预览始终显示完整图，由 `CropOverlay` 单独画框。此前是「预览先按 crop 裁一次、框再按 crop 坐标画一次」，会双重裁切/跳动。
- **裁剪框只在裁剪页显示**：`if (state.cropFree && tab == "裁剪") CropOverlay(...)`，切到调节/滤镜后不再残留裁剪框。
- **预览底座缓存**：打开图片时在 IO 线程一次性生成两档底座：快速档长边 560px、高清档长边 `DoukPrefs.editorPreview`；拖动时直接复制快速底座，不再每帧从原图 `scaleToMax`。
- **快算路径**：新增 `highQualityPreview` 显式状态。`live()` 置 false，`push()`/`commit()`/`undo()`/`redo()` 置 true；预览 effect 按它选底座，拖动时 `delay(4)`，松手立刻按高清底座重算，不再依赖 `lastLiveAt<400ms`（旧逻辑在松手后仍会走快算，导致高清图一直不补）。
- **细节滤镜按需计算**：快算时只保留颜色链；降噪/锐化/清晰度/颗粒仅在「当前正在调的那一项」才执行，其余等松手高清补算。`render(src, s, runtime, quick, activeDetail)` 增加两个默认参数，不影响滤镜缩略图与保存。
- **真机 87159031 验证（build184）**：
  - 自由裁剪拖左上角时截图 `b184_grid.png` 可见九宫格、四角 L 标、无整圈边框；松手后 `b184_gridend.png` 九宫格消失、裁剪框保持。
  - 调节页拖曝光滑杆，中途截图 `b184_live.png` 显示 `+15` 气泡且画面同步变亮，无「计算中」打扰。
  - `dumpsys gfxinfo`（拖动 3 秒）：316 帧，50th 10ms、90th 13ms、95th 15ms、99th 17ms，达到近似实时跟手。

## 18.11 build185：滑杆留白、调节方块居中、裁剪项方块化

- **滑杆统一缩短并留白**：`AdjustSlider` 外层加居中 `Box`，内容宽改为屏宽 84%；滑杆轨道不再贴屏幕左右边。轨道下方新增 10dp 留白，滑杆与下面一行图标/分组之间不再挤在一起。`GradientSlider`（HSL 三条渐变滑杆）同样收到 86% 宽，并加 4dp 行距。
- **调节方块居中**：`AdjustGroups` 的功能卡行移除横向滚动，改为 `fillMaxWidth + Arrangement.spacedBy(8.dp, CenterHorizontally)`；光效 5 张、色彩 6 张、细节 4 张卡片都在屏幕中间居中排列。卡片内的数值与名称改为 `fillMaxWidth + TextAlign.Center`，不再出现左对齐。
- **裁剪项方块化**：`BasicRow` 第二行由胶囊 `CropOptionChip` 改为 `CropOptionCard`（56×64dp、11dp 圆角、18dp 图标、9sp 居中文字）；第一行旋转/翻转按钮也改为居中排列。
- **裁剪顺序**：第二行按 `原始 → 1:1 → 3:4 → 4:3 → 9:16 → 16:9 → 自由` 排列，`原始` 回到第一个；自由裁剪作为最后一个模式卡，开启时显示「自由·开」。
- **实时再提速**：快速预览长边从 560 降到 512；移除拖动时的 4ms 人为延迟，滑杆一动立即提交渲染。实测 `dumpsys gfxinfo`：3 秒 380 帧，50th 9ms、90th 13ms、95th 16ms、99th 20ms。
- **真机 87159031 验证（build185）**：`b185_crop.png` 裁剪页 7 个方块居中、原始在第一个、滑杆明显缩短且与图标留出间距；`b185_adjust.png` 光效 5 卡居中、数值/文字居中；`b185_color2.png` 色彩 6 卡居中；`b185_live.png` 拖动曝光时数值气泡与画面同步变化。



## 18.12 build186：日志异步落盘、USB 授权加固、Android 14 部分相册访问

- **日志不再阻塞主线程**：`DoukLog` 的 `appendText` 从调用线程改为 `DoukLogWriter` 守护线程 + `LinkedBlockingQueue`；`install()` 不再建目录/裁剪/写头；日志页刷新合并到 120ms；`SimpleDateFormat` 改 `ThreadLocal`。日志页导出改到 `Dispatchers.IO`，导出中防重复点击。
- **USB 授权**：`WiredViewModel` 与 `DoukUsbConnector` 的授权 PendingIntent 都锁包名、用 `device.deviceId` 作 requestCode 和 data；`DoukUsbConnector` 收到广播后再校验 `hasPermission` 与相机类型；Android 13+ 类型安全读取 `EXTRA_DEVICE`。
- **备份隐私**：`backup_rules.xml` / `data_extraction_rules.xml` 排除 `douk-logs/`、`nikon_transport.xml`、`douk_settings.xml`、`douk_nikon_ble.xml`。
- **Android 14 部分相册**：Manifest 增加 `READ_MEDIA_VISUAL_USER_SELECTED`；`GalleryScreen` 改用多权限请求，`READ_MEDIA_IMAGES` 或 `READ_MEDIA_VISUAL_USER_SELECTED` 任一授予即可浏览，不再把「仅选中照片」误判为拒绝。
- **其他**：`GalleryFileOps` 换 `androidx.exifinterface`；GPS 坐标格式化固定 `Locale.US`；`NikonViewModel` 去掉静态 `SimpleDateFormat`；修三处可疑缩进。
- **验证**：`assembleDebug` 成功；`lintDebug` 0 error / 51 warning；真机 87159031 安装启动无 FATAL，`session-20261117-222839.log` 正常写入。详细记录见 `docs/security-hardening-2026-11-17.md`。

## 18.13 build187：编辑器实时预览、图库设置、搜索提速、蓝牙稳定与 GPS 反馈

- **编辑器实时预览**：预览渲染从「每次状态变化起一个 `LaunchedEffect`」改为常驻 worker 消费 `Channel(CONFLATED)`，只保留最新一组参数；旧帧用 `AtomicInteger` 序号丢弃，快速拖动时不再排队。快速预览长边 `FAST_PREVIEW_SIDE` 512 → 320，像素量降到 39%；松手仍按设置里的 `editorPreview` 出高清图。数值文本本来就即时更新，现在画面也在同一帧请求内跟着变。
- **设置新增「图库」分组**：下载下面新增 `图库` 卡片，包含「默认打开（相机内 / 应用内 / 手机内）」「资源」「图像编辑」；原来「通用」里的资源、图像编辑两行移除。默认来源写入 `DoukPrefs.galleryDefaultSource`（键 `gallery_default_source`），`GalleryScreen` 用 `GallerySource.fromLabel()` 初始化，未知值回落相机内。
- **搜索提速**：`CameraLanProbe.scan` 默认超时 400 → 300ms，每批并发 64 → 96（最多 3 批，通常 0.3–0.9s）；`searchDevices` 同步先置 `lanScanning=true`，避免下拉指示条在扫描启动前误判结束；扫描异常也会复位 `lanScanning`，不会卡在「正在搜索」。
- **搜到即连**：`searchDevices` 的自动连接改为受 `DoukPrefs.autoConnectKnown` 控制；关闭时只列结果，不再擅自连。搜索结束立即收起下拉指示条，自动连接随后走自己的连接进度。
- **蓝牙反复横跳**：所有 `BluetoothGattCallback`（连接状态 / MTU / 服务 / 描述符 / 读写）先比对 `gatt !== 当前 gatt`，旧 GATT 的迟到回调直接忽略，不再把已 READY 的新连接改回 ERROR；`connect(camera)` 在 CONNECTING/HANDSHAKING/BONDING/READY 时拒绝重复点击；删除「连接相机后自动连接蓝牙」开关、`DoukRoot` 的 60 秒 BLE 重试和连上 Wi-Fi 后的强制 BLE 扫描。
- **GPS 推送反馈**：相机确认收到 GEO 后，设备页显示 UTC 时间（`yyyy-MM-dd HH:mm:ss UTC`）、纬度经度（5 位小数）和中文地名；手动推送先显示「定位已发出，等待相机确认…」，成功后显示「推送成功：相机已确认收到定位」。设备页不再显示推送间隔 chips，间隔仍在「设置 → GPS 数据导入」里调。
- **验证**：`assembleDebug` BUILD SUCCESSFUL；`lintDebug` BUILD SUCCESSFUL；真机 87159031 覆盖安装启动无 FATAL；设置页截图 `_tmp/b187_settings.png` 可见「图库」分组，设备页截图 `_tmp/b187_home.png` 已无推送间隔；编辑器截图 `_tmp/b187_live.png` 为手指按住滑杆时的画面，曝光 +51 与画面变亮同步出现。

## 18.14 build188：蓝牙 GPS 拒收收口、旧配对修复、GATT 写串行化

- **GEO 128 不再无限重连**：`handleGeoRejected()` 替代原来的「128 → 断开 → 重扫 → 重连 → 补推」无上限循环。旧配对（本次会话未在相机确认过）只清一次并重新配对；本次已确认的配对只重连一次；再失败就置 `gpsLoopBlocked`，状态栏给出「把相机重新切到连接至智能设备→配对再点一次」的明确提示，界面不再反复横跳。
- **旧经典配对识别与重配（不再盲删）**：GEO 首次 128 后先按 nsg 的 0x80 流程断开 → 重扫 → 重连 → 重新握手；`checkBondAndFinish()` 发现继承配对后进入 `repairClassicBond()`，保持 BLE 连接做经典发现，**找到真正的经典地址且它仍是 BONDED 时**才反射 `removeBond()`；等 `BOND_NONE` 后重新经典发现 + `createBond()`，让相机重新弹 6 位配对码。经典发现里出现的同款相机 BLE 随机地址一律跳过，不再对 LE 地址 `createBond`（那只会 BOND_NONE 循环）。本次会话刚确认过的同地址配对不会被降级，避免刚配好又被删。
- **GATT 写不再背靠背**：GEO 后不再立刻写 TIME；改为 `onCharacteristicWrite` 收到 GEO 成功回执后 `scheduleTimeSync()` 延时 700ms 写 TIME，避免 Android 同一时刻只能有一个写操作导致挤包。
- **READY 后不抢跑**：`startGpsLoop()` 先 `delay(3s)` 再首推；`finishReady()` 先补推再启动循环，避免 READY 瞬间循环与补推同时写 GEO。
- **定位监听回收**：`stopGpsLoop()` 现在会 `removeUpdates()` 并清空 listener，重连/拒收后不再叠加多个定位监听，降低耗电。
- **经典扫描接收器收口**：`startClassicBonding()` 的 `ACTION_FOUND` / `DISCOVERY_FINISHED` receiver 存进 `classicDiscoveryReceiver`，重复调用先注销，避免多次注册导致重复 `createBond`；`disconnect()`/`dispose()` 一并清理。
- **真机证据**：`session-20261117-231446.log` 记录到相机暴露 0xDE00、握手 READY，手动推送 GEO 被回 `status=128`；旧版随后删除 `3C:BE:E1:53:04:69`，经典发现只找到 BLE 地址 `5F:4E:79:F7:B7:B8` 并对它 `createBond`，反复 `BOND_NONE`。因此改为“先重握手、再经典发现确认、最后才删”，并跳过 LE 地址。\n- **验证**：`assembleDebug` BUILD SUCCESSFUL，APK `59,730,792 bytes`，SHA256 `75FD460872A69CF800E63D7D9A1596A69179D15ED745ED322A142B68B43AEFD6`；真机 87159031 覆盖安装 `versionCode=328 / versionName=3.28-ble-gps-repair`，启动无 FATAL。当前相机已退出配对状态（0xDE00 未暴露），GEO 成功回执仍需相机重新进入「连接至智能设备→配对」后复测。

## 18.15 build189：四层日志、诊断包、储存页预设导入导出

- **进程入口前移**：新增 `DoukApplication` 并在 Manifest 注册。`ACRA.init` 放在 `attachBaseContext`，`xLog`、`DoukLog.install`、`DoukPrefs.init`、Timber 都在 `onCreate` 完成；`MainActivity` 不再重复初始化，只补一条 `MainActivity onCreate` 生命周期日志。
- **四层日志**：
  - Timber 当门面，`Timber.plant` 统一转给 `DoukLog.write`；
  - `DoukLog` 继续写结构化事件 `时间 级别/标签: 事件 key=value`，路径 `filesDir/douk-logs/session-*.log`，单文件 3MB、保留 12 个会话；
  - xLog 只落文件不重复打 logcat，路径 `filesDir/xlog/YYYY-MM-DD`，单文件 2MB、启动时清理 7 天前文件；
  - ACRA 5.11.4 用 `CoreConfigurationBuilder` 运行时配置，不用已移除的 `@AcraCore` 注解；报告含版本、机型、系统、堆栈、logcat、CUSTOM_DATA，开发模式也写盘。
- **网络日志**：新增 `NetworkLoggingInterceptor` 和 `DoukHttp`，只记录方法、主机、路径、状态码、耗时、Content-Length；不记录 Authorization、Cookie 和查询参数。地图瓦片 `downloadTile` 从 `HttpURLConnection` 换成 OkHttp，统一走拦截器。
- **诊断 ZIP**：`DoukDiagnostics` 打包 `README.txt`、`device-info.txt`、`settings/preset.json`、`logs/`、`xlog/`、ACRA 的 approved/unapproved 报告；只留最近 3 个包，不包含照片、视频、LUT 原文件。
- **关于页入口**：「发送诊断日志」用 FileProvider + `ACTION_SEND` 直接拉起微信/邮件/蓝牙分享；「导出诊断日志」用 SAF 保存 ZIP 到手机文件。Manifest 增加 `${applicationId}.fileprovider`，`res/xml/file_paths.xml` 只暴露 `cache/diagnostics`。
- **储存页改造**：移除「运行日志」「连接诊断记录」两行，只保留「应用内图片」「LUT 与滤镜」「合计」；新增「导出预设」「导入预设」。
- **预设格式**：`DoukPreset` 定义 `lenslink-preset-v1`，分组为设备连接 / 下载与回传 / 通用 / 外观 / 图库 / 图像编辑 / 滤镜分类。导出页可以逐组勾选或全选，用 SAF `CreateDocument` 写 JSON；导入用 `OpenDocument`，只覆盖文件里出现的组，导入后 `Activity.recreate()` 刷新设置页。
- **预设隐私**：不打包照片、视频、LUT 文件；蓝牙广播名里的 NUL 填充在导出时清掉，并按「名称+地址」去重；导入只写 SharedPreferences，不复制任何媒体文件。
- **验证**：`assembleDebug` BUILD SUCCESSFUL（`_tmp/build189e.log`）；真机 87159031 覆盖安装 `versionCode=329 / versionName=3.29-log-preset`，启动与导入后均无 FATAL；储存页截图 `_tmp/b189_storage2.png` 已无日志/诊断两行；预设勾选页 `_tmp/b189_preset2.png`；导出文件 `/sdcard/Pictures/lianjing-preset-20261117-2342.json` 2032 bytes，分组齐全；导入同文件提示「已导入 7 组」，页面自动重建；诊断 ZIP `lianjing-diagnostics-20261117-234520.zip` 21382 bytes，含 13 个 `logs/session-*.log`、`xlog/2026-11-17`、`settings/preset.json`、README、device-info；分享面板 `_tmp/b189_share.png` 正确带 ZIP 附件。最终 APK `连镜-LensLink-build189.apk` 65,775,027 bytes，SHA256 `DBC9E756B9C88562929D988E03EEE7275D29391C5D5834C6E8680CA994649641`。
- **配套文档**：`docs/反馈与诊断日志.md`，面向用户和后续维护者，说明怎么发 ZIP、ZIP 里有什么、哪些内容不会被采集、拿到 ZIP 后怎么定位崩溃和连接问题。

## 18.16 build190：GPS 128 根因修复（TIME 心跳 + UTC 时间 + 写槽串行化）

- **根因一：TIME 心跳缺失导致死锁**。旧实现把 `scheduleTimeSync()` 放在 `onCharacteristicWrite` 的 GEO 成功分支里，而 `startGpsLoop()` 在 READY 后 3 秒就发第一包 GEO；第一包被 128 拒收后 TIME 永远不会发，后续每一包 GEO 仍然 128。参考 `Octopustank/HarmoNiLink` 的 `GpsDispatcher.ets`：TIME 是独立心跳轨，前景 1.5 秒/后台 5 秒只发 TIME；GEO 在 READY 后延迟 2.5 秒再发。现在 `finishReady()` 立即启动 `startTimeKeepAlive()`，250ms 后发首拍，之后每 1.5 秒一拍；首包 GEO 推迟到 READY 后 3.8 秒。
- **根因二：TIME 用了本地时间**。旧 `NikonTimePayload.build()` 取本地日历字段又写 `+08:00` 时区，相机把时间理解成「UTC 23:xx + 8h」，与 41 字节 GEO 里的 UTC 字段相差 8 小时。参考 `HarmoNiLink` 的 `TimePayloadGenerator.ets` 与 `jaminxx-aha/ZPill` 的 `time_payload.dart`，改为：年/月/日/时/分/秒全部取 UTC，时区偏移单独写有符号的时/分；夏令时字节固定 0，避免重复加一小时。首拍会把 10 字节载荷写进日志，便于现场核对。
- **根因三：GATT 写槽竞争**。手动推送与自动循环曾在 3ms 内背靠背写两包 GEO，TIME 和 GEO 也会互相挤掉。`writeCharacteristic()` 现在做单写槽串行化：TIME/GEO/ID 写成功后 `gattWriteBusy=true`，`onCharacteristicWrite` 回执释放；超过 2 秒无回执强制释放，避免卡死。握手 PAIR 明确豁免串行化，因为阶段 2 的 indication 可能比阶段 1 的写回调先到，拦掉阶段 3 会直接握手失败。
- **重复服务发现**：部分 ROM 会重复回调 `onMtuChanged`，导致 `discoverServices()` / 订阅 / 握手走两遍。`discoveryStarted` 与 `servicesPrepared` 两个闸门保证每次连接只发现一次、只订阅一次；`openGatt()` 重置这两个闸门。
- **身份一致性**：`sendStage1()` 在「相机广播的 device ID 与已保存 device 不同」时重新生成 nonce（对齐 nsg 的 `NikonPairingEngine.createStage1()` 规则），不再无条件复用旧 nonce，避免相机把 8 字节身份判成另一台设备后拒收 GEO。
- **验证边界**：`assembleDebug` BUILD SUCCESSFUL；真机 87159031 覆盖安装 `versionCode=330 / versionName=3.30-gps-heartbeat`，启动无 FATAL。当前相机不在「连接至智能设备→配对」状态，设备页显示「未启动」；GEO 是否从 128 转为成功，必须在相机重新进入配对界面后复测，不能把本次启动当成 GPS 已修复的证据。

## 18.17 build191：手机连接相机提速与稳定（网关并发 + 网络绑定 + PTP 保活）

- **首选探测改为网关并发**：`CameraLanProbe.priorityTargets()` 把当前 Wi-Fi/有线网络的默认网关、DHCP 服务器、历史 IP、尼康默认 `192.168.1.1`/`192.168.0.1` 一起并发探测。相机开热点时它自己就是网关，通常 300ms 内命中，不再先串行等每个失效历史 IP；参考 `aero-shutter` 的 `DetectAll`。
- **按 Wi-Fi 网络绑定 Socket**：新增 `activeLanNetworks()` / `networkForIp()` / `matchingNets()`。手机同时连着家用 Wi-Fi、移动数据和相机热点时，旧实现用系统默认路由探测，包可能根本到不了相机；现在按目标 IP 的 IPv4 子网匹配对应 `Network`，用 `network.socketFactory.createSocket()` 探测和连接。同网段有多个 Wi-Fi 时优先选未验证网络（相机热点通常无互联网）。
- **PTP 连接网络选择修正**：`NikonPtpClient` 旧逻辑只选「非默认路由」的 Wi-Fi，而相机热点本身往往就是默认网络，会被直接排除，随后落到家用 Wi-Fi/移动数据。改为 `CameraLanProbe.networkForIp(cameraIp)`，按子网选网络。
- **Wi-Fi 事件即时唤醒**：`DoukState.startAutoConnectLoop()` 注册 `ConnectivityManager.NetworkCallback`，Wi-Fi 可用/能力变化后 5 秒去抖触发一次自动连接；仍保留 30 秒兜底轮询，覆盖相机比手机热点晚开机的情况。
- **掉线立即补连**：监听 `isConnected` 的下降沿，真掉线后 1.5 秒触发一次自动连接，不再等满 30 秒；只监听 false 沿，避免连接失败自触发死循环。
- **扫描命中跳过重复预检**：`connectSafely(..., skipPreflight = true)` 用于自动连接和搜索命中路径；端口已由扫描确认，不再额外做 1.5 秒 TCP 预检，直接进入 PTP 握手。手动输入 IP 仍保留重试预检。
- **PTP 空闲保活**：`NikonPtpClient.keepAliveLoop()` 在 `pollEvents=false` 时每 15 秒发一次 `GetDeviceInfo(0x1001)`；读图库/受限会话/`pollEvents=true` 时跳过，失败按真实掉线处理，避免事件通道空闲被相机或路由器回收后第一次点击才发现断线。
- **扫描接口兼容**：保留无 `Context` 的 `scan()` 旧入口做默认路由回退；所有 `DoukState` 调用改为带 `Context` 的新入口。
- **验证边界**：`assembleDebug` BUILD SUCCESSFUL，版本 `3.31-fast-connect`（versionCode 331），APK `65,805,115 bytes`，SHA256 `ADB8C7C67E92E3B7C6D75F24CCA134E3AE5CCD8553007E5B54223EF6CDC0582B`。旧平板 `87159031` 已覆盖安装并启动无 FATAL；启动日志显示 `NET_CALLBACK_REGISTERED` 后约 0.8 秒开始自动扫描，去掉了旧版启动时 2×1.5 秒的重复预检。相机当前不在连接模式（`192.168.125.174:15740` 不通），PTP 真实连接仍需相机进入「连接至智能设备」后复测。

## 18.18 build192：设备页主图、蓝牙权限、控制页实控与重连冷却

- **蓝牙点击无反应**：`DeviceScreen.BleGpsCard` 新增 `RequestMultiplePermissions` launcher 与 `withBlePermission()`，Android 12+ 先申请 `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`/`ACCESS_FINE_LOCATION`，Android 11 及以下只申请定位；权限拒绝时写 `lastHint` 指路系统设置。真机点「搜索相机蓝牙」已进入「正在扫描尼康相机…」，不再是点了没反应。
- **连接反复横跳**：自动重连增加 30 秒冷却（`lastAutoConnectAttemptAt`），握手失败后不再每十几秒顶一次会话；PTP 握手等待从 6 秒放宽到 12 秒，端口连接超时 8 秒。相机端口可达但无响应时，状态文案改为「相机端口可达但 PTP/IP 握手无响应：请先断开 SnapBridge/旧设备，重新进入连接至智能设备」。
- **设备页镜头**：`refreshBodyStatus()` 用 `0xD0E3/D0E4` 焦距、`0xD0E5/D0E6` 最大光圈拼「NIKKOR 16-50mm f/3.5-6.3 · 当前 35mm」，标签改「镜头规格」；`0xD0E0` LensID 无数值映射时不冒充型号。
- **设备页主图**：`PhotoHero` 去掉裁切、阴影、黑色渐变；新增 `removeEdgeWhiteBackground()` 从四边泛洪只挖掉与边缘连通的近白背景，保留机身内部白色字样。真机截图 `_tmp/b192_hero.png` 已无白色画框。
- **档位中文化**：白平衡、曝光模式、驱动模式、测光、对焦枚举补尼康私有 `0x8000+` 映射，兜底改为「未识别…」，不再显示 `8*00`。
- **最新照片缩略图**：`FloatingShutter` 左侧优先显示 `lastCapturedBitmap`，否则取 `cameraPhotos.lastOrNull()` 的 USB/Wi‑Fi 缩略图；点击打开整屏相机内图库（`showCameraGallery` + `GalleryScreen(startInCamera = true)`）。
- **视频控制**：视频模式中央快门改红色，录制中变深红方块；视频页独立显示视频快门/光圈/ISO/曝光补偿与视频测光/白平衡/减震，不再复用拍照曝光组。
- **死按钮接实通道**：`vr`→`0xD138`、`activeD`→`0xD14E`、`flashMode`→`0xD167`、`movieVr`→`0xD1F9`、`movieWb`→`0xD23A`、`movieMetering`→`0xD1AF`、`movieShutter`→`0xD1A8`、`movieAperture`→`0xD1A9`、`movieIso`→`0xD1AA`、`movieEv`→`0xD1AB`；`extraProps` 读描述、当前值、枚举与 writable，`applyExtra` 真实下发并回滚失败值。
- **验证边界**：`assembleDebug` BUILD SUCCESSFUL，版本 `3.32-device-fixes`（versionCode 332），APK `65,881,215 bytes`，SHA256 `8AE0DCA468548197BA104D1148568D3D4CD6BEE049D88B2F30E1728C1BBA9FEC`；旧平板 `87159031` 覆盖安装启动无 FATAL。设备页蓝牙按钮真机已进入扫描态；控制页拍照/视频分组与红色快门截图确认。当前相机 `192.168.125.174:15740` TCP 可连但 PTP/IP 握手持续超时，日志 `session-20261118-134409.log`；这不是 UI 问题，需相机侧断开旧会话后复测，不能把 build192 当作已连上相机的证据。


