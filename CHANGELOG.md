# Changelog

## 0.2.6

- 移除内置的 5 个第三方 LUT，改为用户自行导入，应用不再随包分发任何 LUT。
- 导出水印去掉 NIKON 字样，复古铭牌模板改用「连镜」。
- 应用名统一为「连镜」，安装包改用拼音命名。
- 补齐第三方组件声明，并在 README 与应用内增加非官方、无关联声明。

## 0.2.0

连镜 0.2.0 更新日志

一、连相机
优化连接相机的能力

二、拍摄与监看
优化lut和监视界面

三、图库
增加双指缩放、长按多选和取消勾选。
增加图片-详细-hdr检查

四、图片编辑（测试）
增加hdr图片显示
增加普通 SDR 图导出HDR效果
设置增加默认导出格式（JPG / PNG）、编辑默认格式（JPG / HEIC / RAW），可选「每次问我」。

五、视频
优化图库视频格式的支持
支持套lut导出视频

六、bug
修改优化了已知bug

## 3.26 baseline

- Nikon PTP/IP support for camera AP, phone-hotspot STA, and shared-router modes.
- Live-view frame retrieval, throttled decode queue, and live transport metrics.
- Optional new-photo detection and JPEG retrieval.
- ZIP export for bounded connection diagnostics.
- Full-screen preview and existing LUT, frame, watermark, USB PTP, and SD import workflows.

This is a summary of code paths present in the baseline, not a claim that every feature works on every Nikon body. Known limitations and the Z 30 validation scope are recorded in `README.md`.

## Open-source preparation

- Added Apache-2.0 license and third-party/protocol research notices.
- Removed a machine-specific JDK path, pinned the official Gradle 8.7 distribution checksum, and strengthened ignored-file rules.
- Excluded private connection traces and staged photo files from Android backups while preserving restorable pairing preferences.
- Added project build, privacy, architecture, contribution, and security documentation.
- Added a release checklist that identifies remaining ownership, dependency-notice, build, and device-validation steps.
