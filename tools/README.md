# Nikon PTP/IP 联调工具

这两个脚本只依赖 Python 3 标准库，用来在没有尼康相机时验证应用的协议栈。它们不会修改相机参数、触发拍摄或写入相机。

## 1. 模拟尼康相机

```bash
python tools/mock_nikon_ptp.py --advertise
```

默认监听 `0.0.0.0:15740`，并在局域网广播 `_ptp._tcp.local`。模拟器目前实现：

- PTP/IP `InitCommandRequest` / `InitCommandAck`
- 事件通道 `InitEventRequest` / `InitEventAck`
- `OpenSession`（0x1002）
- `GetDeviceInfo`（0x1001），默认返回 Nikon / Z 50_2 / MOCK 1.0（使用尼康真实的 PTP 机型写法，App 才能按 Z50II 正确识别）
- 心跳 `0x90C8` 和应用模式 `0x9435` 返回 OK
- 未知操作返回 `0x2005 OperationNotSupported`，不伪装成完整相机

常用参数：

```bash
# 指定监听地址、端口、机型（机型建议用尼康 PTP 写法，如 Z 50_2 / Z 6_3）
python tools/mock_nikon_ptp.py --host 0.0.0.0 --port 15740 --model "Z 50_2"

# 不开 mDNS，手机端手动填电脑 IP
python tools/mock_nikon_ptp.py

# 模拟相机拒绝配对，reason=1
python tools/mock_nikon_ptp.py --reject 1

# 打印每个包的收发
python tools/mock_nikon_ptp.py -v
```

手机和电脑需要在同一局域网，Windows 防火墙要放行 TCP 15740；mDNS 需要放行 UDP 5353。若电脑有多个网卡，用 `--mdns-ip` 指定广播的地址：

```bash
python tools/mock_nikon_ptp.py --advertise --mdns-ip 192.168.1.20
```

模拟器只用于握手联调。实时取景、拍摄、图库、属性读写仍要以真机为准；需要时再按实际抓包逐项补操作码，不要用假数据掩盖协议差异。

## 2. 只读探测相机

```bash
python tools/ptp_probe.py --host 192.168.1.1
```

执行流程：

1. TCP 连接相机 `15740`
2. 发送 `InitCommandRequest`，打印 `InitCommandAck` 或 `InitFail` 原因码
3. 打开事件通道
4. `OpenSession`，打印响应码
5. `GetDeviceInfo`，解析厂商、型号、固件、序列号、操作码和属性码
6. 逐包打印 `type`、长度和 `0x2001` 等响应码

常用参数：

```bash
python tools/ptp_probe.py --host 192.168.1.1 --port 15740
python tools/ptp_probe.py --host 192.168.1.1 --timeout 8 -v
```

探针不会写属性、不会拍照、不会切换应用模式。`OpenSession` 是 PTP 会话建立所必需的，会占用相机的一次连接名额；用完关闭脚本即可释放。

## 3. 本机自测

开两个终端：

```bash
# 终端 1
python tools/mock_nikon_ptp.py --host 127.0.0.1 --port 15740 -v

# 终端 2
python tools/ptp_probe.py --host 127.0.0.1 --port 15740 -v
```

正常结果会看到：

```text
InitCommandAck connection=1 ...
response tx=1 code=0x2001 OK
response tx=2 code=0x2001 OK

相机信息
  厂商      : Nikon
  型号      : Z 50_2
...
```

如果要验证失败路径：

```bash
python tools/mock_nikon_ptp.py --reject 1
python tools/ptp_probe.py --host 127.0.0.1
# camera rejected handshake: InitFail reason=1
```
