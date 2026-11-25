# Third-party notices / 第三方声明

连镜（LianJing）使用或参考了下列第三方组件与公开资料。本文件用于满足相应许可证的署名与声明要求。

## 1. Runtime dependencies / 运行时依赖

| Component | Version | License |
| --- | --- | --- |
| Gson | 2.10.1 | Apache-2.0 |
| Timber | 5.0.1 | Apache-2.0 |
| xLog | 1.11.0 | Apache-2.0 |
| ACRA (acra-core) | 5.11.4 | Apache-2.0 |
| OkHttp / logging-interceptor | 4.12.0 | Apache-2.0 |
| AndroidX ExifInterface | 1.3.7 | Apache-2.0 |
| AndroidX DocumentFile | 1.0.1 | Apache-2.0 |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 |
| AndroidX Lifecycle Runtime KTX / Compose | 2.8.2 | Apache-2.0 |
| AndroidX Activity Compose | 1.9.0 | Apache-2.0 |
| Jetpack Compose (BOM) | 2024.12.01 | Apache-2.0 |
| AndroidX Navigation Compose | 2.7.7 | Apache-2.0 |
| Haze (dev.chrisbanes.haze) | 1.2.0 | Apache-2.0 |
| Coil (coil-compose / coil-base) | 2.6.0 | Apache-2.0 |
| Telephoto (zoomable, zoomable-image, zoomable-image-coil) | 0.13.0 | Apache-2.0 |
| Telephoto sub-sampling-image（随 zoomable-image 传递引入） | 0.13.0 | Apache-2.0 |
| Molecule runtime（Telephoto 传递依赖） | 1.4.3 | Apache-2.0 |
| Accompanist DrawablePainter（Telephoto 传递依赖） | 0.34.0 | Apache-2.0 |
| Material Icons Extended | Compose BOM | Apache-2.0 |
| LibRaw | 0.23.0 Snapshot202611 | CDDL-1.0（本项目选择）或 LGPL-2.1 |
| LibRaw-CMake（构建包装脚本） | 随工程内嵌 | BSD-3-Clause |

LibRaw 以源码形式随工程编译（`app/src/main/cpp/libraw/`），包含 `nikonhe.cpp` 对尼康 Z50II HE/HE★ NEF 的解码支持。许可原文见同目录 `LICENSE.CDDL`、`LICENSE.LGPL` 与 `COPYRIGHT`。上游：https://github.com/LibRaw/LibRaw

LibRaw-CMake 是随工程内嵌的构建包装脚本（`app/src/main/cpp/libraw-cmake/`），仅负责把 LibRaw 接入 CMake 并生成 `libraw::libraw` 目标，本身不含 LibRaw 源码。许可为 BSD-3-Clause，原文见同目录 `LICENSE`，已原样保留。

Test-only: JUnit 4.13.2 (EPL-1.0), AndroidX Test JUnit 1.3.0, Espresso 3.7.0 (Apache-2.0). They are not shipped as application features.

Apache-2.0 license text: https://www.apache.org/licenses/LICENSE-2.0

### 1.1 Coil / Telephoto 使用说明

- Coil 用于图库缩略图的解码与内存缓存（`ui/douk/GalleryThumbs.kt`），内存缓存按进程堆 15% 封顶，不写磁盘缓存；Coil 的 API 以 Gradle 依赖形式引用，**未修改其源码**。
- Telephoto 用于「细节查看」页的平移缩放（`ui/douk/ImageDetailScreen.kt`），同样以 Gradle 依赖引用，未修改源码；当前版本内**未启用**其 `sub-sampling` 分块渲染路径。
- 两者均为 Apache-2.0，保留原作者版权与许可声明；本项目未使用其项目名称、Logo 或图标作为自身品牌。

## 2. Protocol research references / 协议研究参考

The following public projects were consulted for protocol facts (UUIDs, operation codes, field layouts, timing). No source code, fixtures, or binary assets from these projects are copied into or bundled with this application, unless explicitly stated otherwise.

| Project | License | What was referenced |
| --- | --- | --- |
| hurui200320/nsg | AGPL-3.0 | Nikon BLE pairing / GPS protocol behavior observations |
| Octopustank/HarmoNiLink | MulanPSL-2.0 | BLE GPS timing and TIME heartbeat behavior observations |
| jaminxx-aha/ZPill | MIT | Nikon BLE time payload layout observations |
| subhashraveendran/aero-shutter | MIT | Nikon PTP/IP LAN probing approach observations |
| open-nikonz/zf-capture-one-helper | Apache-2.0 | Nikon pairing operation sequence corroboration |
| libgphoto2 | LGPL-2.1+ | General PTP operation facts; not linked or packaged |

连镜是独立实现，未复制上述 AGPL/MulanPSL 项目的源代码。若你认为本项目的某段实现与上述项目构成实质性相似，请通过 Issue 联系，我们会核查并调整。

## 3. Bundled assets / 内置资源

- 本应用**不内置任何第三方 LUT**。`assets/luts` 目录已移除，所有 `.cube` / `.xlog` LUT 均由用户自行导入，其许可证由用户自行确认。
- 应用图标为原创设计。

## 4. Trademarks / 商标

Nikon、Nikon Z 等名称与标识归尼康株式会社所有，仅用于说明兼容的相机型号。连镜是**非官方**第三方应用，与尼康株式会社没有任何关联、隶属、授权、赞助或背书关系。

## 5. Review-only, not bundled / 仅评估参考，未打包

以下项目在选型阶段被评估过，但**没有**任何源码、资源、二进制或模型权重进入本应用。列入此节是为了记录结论，避免后续误以为已经接入。

| Project | License | Decision |
| --- | --- | --- |
| LightCraft（artcraft-android） | 主仓库 MIT OR Apache-2.0，但内含非 OSI 的 SAM3 权重、GPL-3.0 降噪模型、OFL 字体、非开源品牌资产 | 只参考页面结构与交互；未复制源码/字体/Logo/权重 |
| AndroidX Media3 | Apache-2.0 | 暂缓：需要 compileSdk 35/36 构建链，等升级后再评估视频编辑 |
| ClearCut | MIT | 只参考视频管线与导出校验思路；若未来借用代码片段，须在此处补 MIT 版权声明 |
| gpupixel | Apache-2.0 | 计划仅作为滤镜/美颜效果层接入，不接 RAW 解码与显影主链路；本轮未接入 |
| libultrahdr | MIT OR Apache-2.0 | 计划用于替换手写 MPF/GainMap 容器；本轮未接入，需先完成 NDK 交叉编译与 JNI 封装 |
| CapCraft | AGPL-3.0 | 仅看架构，未复制代码 |
| RawTherapee / darktable | GPL-3.0 | 仅参考调色与 RAW 处理思路，未复制代码 |
| libgphoto2android | CC-BY-4.0（非标准软件许可） | 仅作交叉编译参考 |
| camera-connect-sdk | 仓库根目录无 LICENSE（视为保留全部权利） | 仅参考 |

