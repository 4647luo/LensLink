# 连镜 LensLink 审计与修复记录（2026-11-17 / build186）

## 本轮范围

用户要求按 10 个 Android Skill 检查并修掉可能存在的 bug。本轮以代码级缺陷为主，不包含 AGP 9 迁移、不包含 Play 上架全量合规审计。

## 已修复

### 1. 日志写入造成的 ANR / 卡顿风险（anr-audit-and-fix、jetpack-compose-performance）

- `DoukLog.write()` 以前在调用线程里 `appendText` 落盘。网络回调、界面点击都可能在主线程，日志量大时每个日志都等一次磁盘。
- 现在所有写盘进入 `LinkedBlockingQueue`，由单独的 `DoukLogWriter` 守护线程落盘；主线程只做内存缓冲和一次非阻塞入队。
- `install()` 不再在主线程 `mkdirs`、`trim`、写文件头；目录创建和历史日志裁剪移到写线程。
- 日志页刷新由「每条日志 +1」改为 120ms 合并刷新，最多约 8 次/秒，避免网络日志把 Compose 一直重组。
- `SimpleDateFormat` 改为 `ThreadLocal`，修掉多线程同时格式化时间戳可能串位/异常的问题。
- `DoukLogScreen` 的两处「导出日志」从主线程同步读文件 + 写 MediaStore，改为 `Dispatchers.IO`，导出中防止重复点击。

### 2. USB 授权的 PendingIntent 安全与多设备错投（android-intent-security）

- `WiredViewModel` 的授权 PendingIntent 原来没有 `setPackage`、requestCode 固定 0；已改为：
  - `setPackage(context.packageName)`；
  - `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`；
  - requestCode 使用 `device.deviceId`，并给 Intent 加 `douk-usb://<deviceId>` data，避免多台 USB 设备共用同一个 PendingIntent。
- `DoukUsbConnector` 同样改为按 `deviceId` 区分；并在收到 `ACTION_USB_PERMISSION` 后再次调用 `UsbManager.hasPermission(device)` 和 `looksLikeCamera(device)`，不再只信广播里的 `EXTRA_PERMISSION_GRANTED`。
- `WiredViewModel` 读取 `EXTRA_DEVICE` 增加 Android 13+ 类型安全分支，去掉泛型 `getParcelableExtra`。

### 3. 备份把相机配对身份 / 已知设备 / GPS / 日志带走（android-permissions-security）

`backup_rules.xml` 与 `data_extraction_rules.xml` 新增排除：

- `file/douk-logs/`：运行日志；
- `sharedpref/nikon_transport.xml`：PTP 主机 GUID、相机压缩值；
- `sharedpref/douk_settings.xml`：已知相机 IP、已知蓝牙设备；
- `sharedpref/douk_nikon_ble.xml`：蓝牙 MAC、配对 device/nonce、最近推送位置。

原有 `nikon-traces/`、`nikon-photo-inbox/` 排除保留。

### 4. NikonPtpClient 主线程同步落盘（anr-audit-and-fix）

- `restorePairingIdentity()` 和 `startNikonWiWatch()` 里保存 `hostGuid` 的 `commit()` 改为 `apply()`。`apply()` 会同步更新内存、异步写盘，不会再在连接初始化线程上等 fsync。
- 相机压缩值的 `commit()` 仍在 IO 线程调用（`NikonViewModel` 已 `withContext(Dispatchers.IO)`），保留同步写入的可靠性。

### 5. Android 14「仅选中照片」被误判为无权限（android-permissions-security、play-policy-insights）

- Manifest 增加 `READ_MEDIA_VISUAL_USER_SELECTED`。
- `GalleryScreen` 的相册权限从单一 `RequestPermission` 改为 `RequestMultiplePermissions`：
  - Android 14+ 同时请求 `READ_MEDIA_IMAGES` 与 `READ_MEDIA_VISUAL_USER_SELECTED`；
  - 任一授予即视为有访问权；
  - Android 13 仍只请求 `READ_MEDIA_IMAGES`，Android 12 及以下仍用 `READ_EXTERNAL_STORAGE`。
- 这样用户选「仅选中照片」后，图库不再反复弹「需要读取手机相册」。

### 6. 其他静态问题

- `GalleryFileOps` 的 `android.media.ExifInterface` 改为 `androidx.exifinterface.media.ExifInterface`，避开旧实现的已知安全问题。
- `GalleryViewer` 的 GPS 坐标 `String.format` 显式使用 `Locale.US`，避免部分地区用逗号小数导致坐标显示歧义。
- `NikonViewModel` 去掉静态 `SimpleDateFormat(Locale.getDefault())`，改为调用处创建，修掉线程安全和运行中切换语言不生效的隐患。
- 修掉 `FtpPushReceiver`、`SdImportProcessor`、`UsbPtpClient` 三处可疑缩进（逻辑未变，只让代码结构可读、避免后续误改）。

## 验证

- `:app:assembleDebug`：BUILD SUCCESSFUL（build186 最终日志 `_tmp/build186c.log`）。
- `:app:lintDebug`：BUILD SUCCESSFUL，0 error、51 warning；`SelectedPhotoAccess`、`ExifInterface`、`DefaultLocale`、`ConstantLocale` 四类告警已消失。
- 真机 `87159031` 安装、启动成功，`topResumedActivity=com.lutcamera.lutcamerapro/.MainActivity`，logcat 无 `FATAL EXCEPTION`。
- 启动后新会话日志正常生成：`files/douk-logs/session-20261117-222839.log`，文件头、时间戳、包名、后续连接日志齐全，说明异步写盘链路可用。

## 明确未做 / 已知剩余项

- **未做 AGP 9 迁移**：项目当前 AGP 8.5.1，不属于 AGP 9 项目；按 `agp-9-upgrade` 要求，用户未要求迁移时不自行升级。
- **未把 targetSdk 升到 35**：`edge-to-edge` Skill 的目标是 targetSdk 35+，但当前是 34。升 35 需要同时评估 compileSdk、AGP、Compose 与权限行为，属于单独升级任务，本轮没动。
- **未跑 Play 全量合规审计**：本轮没有执行 `play-policy-insights` 的 `orchestrator.py` 流程，因此不能宣称完成 Play 上架合规审计；上面只修了其中能静态确认的两项：部分相册访问、备份隐私。
- **未改全局配色对比度**：浅色主题下 `textTertiary`（#B0B4BC on #FFFFFF）对比度约 2.1:1，低于 WCAG AA 对小字的 4.5:1；这是既有视觉体系的一部分，改动会影响全站观感，未在本轮未经确认地改。
- **未改触摸目标尺寸**：编辑器部分胶囊高度约 34dp，低于 44dp 建议；考虑到用户此前反复要求紧凑，未单方面放大。
- **未改 Blowfish/ECB**：`NikonBleProtocol` 使用 `Blowfish/ECB/NoPadding` 是尼康 BLE 协议要求，不是可替换的应用加密；lint 的 `GetInstance` 警告保留。
