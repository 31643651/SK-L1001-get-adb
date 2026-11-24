# C10H 从零获取 root ADB（免拆机 · 通用版）

> 适用设备：创维 SK-L1001 / 天益智屏 C10H（`p291_iptv`），Android 9，`userdebug`，`test-keys`，SELinux Disabled
> 目标：**不拆机、不刷机、不改 system**，从"完全没有 ADB"到 `adb connect` 后 `uid=0(root)`
> 核心前提：**主机（PC/中转机）与设备之间有一条专用的有线链路**，互联网走另一条出口
> 原理一句话：**SELinux 关闭 → 普通应用就能 `setprop` → init 自己执行 `start adbd` → `ro.debuggable=1` 让它以 root 跑**

---

## 0. 先看结果

```
$ adb connect 10.10.0.162:5555
connected to 10.10.0.162:5555
$ adb shell id
uid=0(root) gid=0(root) groups=0(root),1004(input),1007(log),1011(adb),...
```

之后可以做的事：换自研桌面、裁掉厂商组件、静默安装、离线语音……见第八、九章。

---

## 1. 网络接入：三种都行，本质相同

设备侧固定是 **有线 `eth0`，静态 `10.10.0.162/24`，网关/DNS `10.10.0.1`**。
主机侧只要能给它一条"10.10.0.1/24 的专用链路"就行，**用几块网卡、有没有 WiFi 都不重要**。

### 方案 A：双网口（最干净，无需中转机）
```
[互联网/局域网] ── 网口1（DHCP，如 192.168.1.x）
[设备 10.10.0.162] ──网线── 网口2（静态 10.10.0.1/24）
```

### 方案 B：单网口 + WiFi（笔记本最常见）
```
[互联网] ── WiFi（DHCP）
[设备 10.10.0.162] ──网线── 唯一网口（静态 10.10.0.1/24）
```

### 方案 C：单网口 + 无 WiFi（本项目当初的情况，才需要中转机）
```
[互联网] ── 路由器 ── WiFi ── [中转机 wlan0 192.168.0.170]
                                 └─ eth0 10.10.0.1/24 ──网线── [设备 10.10.0.162]
```
> **方案 C 只是 B 的替代，不是必需。**

### 主机侧要做的事（三方案一致）
| 项 | 内容 |
|---|---|
| 设备侧网卡 IP | `10.10.0.1/24`（**必须**，设备网关指向它）|
| DHCP（可选） | 设备静态 IP 时不需要；走 DHCP 才需要（`10.10.0.50–150`）|
| DNS 劫持 | `appmarket.zhongping.jsinfo.net` → `10.10.0.1` |
| 三个服务 | `apk_server:6060`、`cmd_server:8000`、主机侧 `adb` |

---

## 2. 主机准备（按系统选一套）

### 2.1 Linux 主机（或方案 C 的中转机）
```bash
sudo ip addr replace 10.10.0.1/24 dev eth0     # 换成你的"设备侧网卡名"
sudo ip link set eth0 up
sudo sysctl -w net.ipv4.ip_forward=1

sudo tee /etc/dnsmasq.d/c10h.conf > /dev/null <<'EOF'
interface=eth0
bind-interfaces
dhcp-range=10.10.0.50,10.10.0.150,255.255.255.0,12h
dhcp-option=option:dns-server,10.10.0.1
address=/appmarket.zhongping.jsinfo.net/10.10.0.1
EOF
sudo systemctl restart dnsmasq

mkdir -p ~/c10h/apk && cp helper_qtfm.apk ~/c10h/apk/
cd ~/c10h
nohup python3 apk_server.py > apk_server.log 2>&1 &
nohup python3 cmd_server.py > cmd_server.log 2>&1 &
```

### 2.2 Windows 主机
```powershell
netsh interface ip set address name="以太网 2" static 10.10.0.1 255.255.255.0

cd D:\c10h
start /b py dns_hijack.py        # UDP 53：appmarket → 10.10.0.1
start /b py apk_server.py        # TCP 6060：任何 GET 返回 apk\ 里的 APK
start /b py cmd_server.py        # TCP 8000：下发指令
```
> Windows 无 dnsmasq，用附录 A.3 的 `dns_hijack.py`；设备本身静态 IP，**不需要 DHCP**。

### 2.3 macOS 主机
```bash
sudo ifconfig en7 inet 10.10.0.1 netmask 255.255.255.0   # en7 = 设备侧网卡
# DNS/DHCP 用 dnsmasq（brew install dnsmasq），配置同 2.1
```

**成功标志**：设备侧网卡显示 `10.10.0.1`；`ss -ltnp | grep -E '6060|8000'` 有监听。

---

## 3. 五步快速入门

### Step 1 · 设备连线上电（1 分钟）
```bash
ping -c 2 10.10.0.162
```
**成功标志**：通（1–2 ms）。不通查：网线、网卡 IP、设备有线网络、防火墙。

### Step 2 · 用设备商店装"我们的助手"（5 分钟）
1. 设备屏幕打开**应用商店**。
2. 随便点一个应用**下载/安装**。
3. 商店请求 `appmarket.zhongping.jsinfo.net:6060` → DNS 被劫持到 `10.10.0.1` → **实际拿到 `apk/helper_qtfm.apk`**。
4. 确认安装。

**成功标志**：设备出现 **"工厂助手"** 图标。
> 已装过助手可跳过本步。商店下载走**明文 HTTP 6060**，不需要证书。

### Step 3 · 下发"提权命令链"（5 分钟）
确认 `cmd_server` 在跑，写指令：
```bash
cat > ~/c10h/helper_cmd.json <<'EOF'
{
 "seq": 1,
 "comment": "unlock root adb",
 "cmds": [
  {"op": "shell", "cmd": "setprop service.adb.root 1"},
  {"op": "shell", "cmd": "setprop service.adb.tcp.port 5555"},
  {"op": "shell", "cmd": "setprop persist.adb.tcp.port 5555"},
  {"op": "shell", "cmd": "setprop persist.sys.usb.config mtp,adb"},
  {"op": "shell", "cmd": "setprop sys.usb.config mtp,adb"},
  {"op": "shell", "cmd": "sleep 3; getprop init.svc.adbd; netstat -l 2>/dev/null | grep 5555"},
  {"op": "note", "text": "=== unlock done ==="}
 ]
}
EOF
```
设备上打开**工厂助手** → **【⑤ 实时下发模式】**；20 秒后：
```bash
tail -5 ~/c10h/helper_report.log
```
**成功标志**：`adbd_svc=running` + `:::5555 LISTEN`。

### Step 4 · 连上并验证 root（1 分钟）
```bash
adb connect 10.10.0.162:5555 && adb shell id     # uid=0(root)
```

### Step 5 · 免杀 + 开机自启（3 分钟）
```bash
adb shell 'echo $(date +%s)000 > /data/local/adbauth.txt'
```
文件存在即免杀（实测 8 分钟无刷新仍存活）；**恢复出厂会清 `/data`，必须重写**。

开机自启（可选，需改 vendor 镜像，见 `2026-11-22-c10h-adb-init-autostart.md`）：
```
dd vendor → 主机 loop 挂载 → 写 /vendor/etc/init/adbautostart.rc → 整镜像写回 → 重启
```

---

## 4. 一键检查清单

| # | 检查 | 命令 | 期望 |
|---|---|---|---|
| 1 | 设备侧网卡 | `ip -4 addr show <网卡>` | `10.10.0.1` |
| 2 | 假商店 | `ss -ltnp \| grep 6060` | LISTEN |
| 3 | 指令服务 | `ss -ltnp \| grep 8000` | LISTEN |
| 4 | 设备可达 | `ping -c 2 10.10.0.162` | 0% loss |
| 5 | 助手已装 | 屏幕可见"工厂助手" | ✅ |
| 6 | 回传 | `tail helper_report.log` | `adbd_svc=running` |
| 7 | 端口 | 回传 netstat | `:::5555 LISTEN` |
| 8 | root | `adb shell id` | `uid=0(root)` |

---

## 5. 失败排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 设备 ping 不通 | 链路/网段/网卡/防火墙 | 网线直连；网卡设 `10.10.0.1/24`；放行 ICMP |
| 商店点下载无反应 | DNS 未生效 | 设备上 ping 该域名必须到 `10.10.0.1` |
| 安装报"解析包错误" | APK 无效/未签名 | `aapt2 dump badging` 校验后重签 |
| 助手没有 ⑤/⑥ | 装的不是本项目助手 | 换 `helper_qtfm.apk` |
| 回传 `adbd=stopped` | 属性触发器未命中 | 重发 `sys.usb.config none` → `mtp,adb` |
| `adb connect` 拒绝 | 5555 未监听 | 看回传；确认 `service.adb.tcp.port=5555` |
| 连上几分钟后掉线 | 厂商 180 s 逻辑 | 执行 Step 5 |
| 恢复出厂后失效 | `/data` 被清 | 重跑 Step 3 + 5 |

---

## 6. 为什么这样能成功（30 秒原理）

1. `adbd` 是 `disabled` 服务，正常只靠属性触发器；本机触发链在 `configfs=0` 分支 `write` 失败会中断 action ⇒ "开机能不能起"原本靠运气。
2. `getenforce=Disabled` ⇒ 属性服务对**非 `ctl.*`** 属性**不校验 uid** ⇒ 普通应用能 `setprop`。
3. `ctl.start adbd` 受 uid 限制（不行），**但 `on property:` 是 init(uid 0) 执行** ⇒ 写 `sys.usb.config mtp,adb`，init 替我们 `start adbd`。
4. `ro.debuggable=1` ⇒ adbd 以 root 跑；`/vendor/build.prop` 已有 `service.adb.tcp.port=5555`。
5. **绝不** `settings put global adb_enabled 1`：那是 180 秒陷阱的点火开关。

---

## 7. 两个自研应用

### 7.1 C10hLauncher · 自研桌面

| 项 | 内容 |
|---|---|
| 包名 | `com.c10h.launcher` |
| 定位 | 替代电信 zp 桌面，作为电视盒子的 HOME（工作区 + 应用抽屉 + 自研设置）|
| 签名 | **平台签名**（与设备 framework 证书一致），因此拿到 33 项权限（含 `INJECT_EVENTS`、`INSTALL_PACKAGES`、`MODIFY_AUDIO_ROUTING`、`WRITE_SECURE_SETTINGS`）|
| 源码 | `dist/C10hLauncher/`（`build_apk.py`，无 Gradle，aapt2+javac+d8+apksigner）|
| 安装 | `scripts/promote_platform_signed.py`（签名→装→设默认桌面→校验权限）|

**模块一览**
| 模块 | 作用 |
|---|---|
| `home` | 工作区/分页/文件夹/拖拽排序/页面指示器/壁纸 |
| `installer` | `InstallerActivity`（APK 安装页）+ `FileBrowserActivity`（文件管理）|
| `nav` | 悬浮导航、边缘手势、电源菜单、息屏、截屏、最近任务 |
| `eq` | 全局音效（`DynamicsProcessing`，session 0）|
| `qs` | 快捷设置面板 |
| `settings` | 自研设置、应用信息（强制停止/清除数据/权限）、最近任务列表 |
| `block` | VPN 拦截服务 + DNS 代理 + 规则导入（自建"防火墙/去广告"）|
| `badge` | 通知角标（NotificationListener）|
| `appstart` | 开机自启管理 |
| `update` | 自更新接收器 |

**已知问题**
- 壁纸按源尺寸解码（3840×1920×3 份 = 86 MB），冷启动 PSS 63.5 MB、长会话可到 172 MB；
- 长会话 `ViewRootImpl` 从 2 涨到 7（疑似悬浮层未释放）；
- 真息屏/静默安装仍是设计稿（第八章）。

### 7.2 C10hHelper · 自研助手

| 项 | 内容 |
|---|---|
| 包名 | `com.qtfm.tv.dxzp`（**马甲成"蜻蜓FM"**，避免被商店/系统拦）|
| 定位 | **没有 ADB 时唯一的执行入口**——通过应用商店投喂安装，提供命令执行原语与一键恢复按钮 |
| 源码 | `dist/C10hHelper/`（同一套 `build_apk.py`）|
| 关键能力 | 在自身进程内执行 `sh`（uid≈10049），**不需要 root 就能 `setprop`**（SELinux 关闭）|

**六个按钮**
| 按钮 | 功能 |
|---|---|
| ① 下载配置文件（写卡）| 从 `10.10.0.1:8000` 拉 `factorytest_config.enc` 并写 `/sdcard/factoryTest/` |
| ② 列出系统应用 / ACTIVITY | 枚举包与组件，排查用 |
| ③ 快捷启动 FACTORYTEST | 拉起厂商工厂测试入口（TestLocation）|
| ④ 检查写卡文件 | 校验写卡结果 |
| ⑤ 实时下发模式 | **每 12 s 轮询 `10.10.0.1:8000/helper_cmd.json`，执行并把结果 POST 回 `/report`** |
| ⑥ 恢复 ADB（5555+ROOT）| 本地执行 Step 3 那条 setprop 链，约 7 秒自愈 |

**为什么它是"钥匙"**
```
没有 adb → 屏幕商店是唯一装 APK 入口
  → 投喂助手（马甲包过审/安装）
  → 助手用 shell 原语执行 setprop
  → init 拉起 root adbd
```
> 恢复出厂会清 `/data`，助手需重装（`helper_qtfm.apk`）。

---

## 8. 折腾全过程（从拿到 ADB 到现在）

### 11-18 · 打通 ADB 与 root
- 设备无 adb 入口 → 用**商店 + 域名劫持**投喂助手；
- 发现 **`setprop sys.usb.config mtp,adb`** 能触发 init 起 adbd，配合 `ro.debuggable=1` 直接拿到 **root shell**；
- 首次固化尝试：`AutoStartHelper`（AlarmManager +5s → 拉起工厂测试）链，脆弱。

### 11-19 · 摸清系统 + 踩到 180 秒陷阱
- 全系统画像（25 组）：权限、服务、后门（`SKY_AUTO_SCRIPT` 签名 U 盘脚本通道）、TMC 云管控；
- 定位 **180 s 杀 adbd 陷阱**：`Settings.global` 通配 Observer 被系统自身写入（如每日 22:00 勿扰翻转）触发，180 s 后杀 adbd；断网掐不住；
- 分析 recovery 镜像（魔改菜单，**没有 adb/sideload 项**，只有恢复出厂）；
- 一次恢复出厂事故：`/data` 全清、桌面/助手/配置全丢，靠商店投喂链救回。

### 11-22 · 三个硬成果
1. **ADB init 级自启**：改 `/vendor` 镜像加 `adbautostart.rc`，实测 **3.03/3.09/3.06 s** 起 adbd，`uid=0`；
2. **平台签名提权**：本地 `platform.pk8` 与设备证书一致 → 自研桌面 18 项 signature 权限，不需要 priv-app；
3. **adbd 免杀根因**：`AdbUtils` 读 `/data/local/adbauth.txt`，文件存在即不杀（写到就永久有效）。

同期踩坑：
- 禁用系统安装器后卡开机（疑似未完成安装会话）；
- "设备反复重启"假象 = 急救脚本每次抢到 adb 就 `reboot`（`bootreason=reboot,shell`）；
- 恢复出厂后 6 步重建流程定型。

### 11-23 / 11-24 · 生态与体验
- **原厂商店 32 个 App 全量**（从 zp 的 MMKV 缓存解析），确认下载域 `stpres.189smarthome.com`；
- 商店路线验证：应用宝（卡，弃）、当贝市场（流畅但系统安装器被锁，绕过 = 下载后 `pm install`）；
- 实装并验证 4 个 TV 应用（芒果、云视听极光、CIBN酷喵、B站）；
- **自研跑分**：单核 154.6 Mops/s、多核 609.4、内存 2091 MB/s、2D 25.5 kops/s；
- **内存体检**：桌面 172 MB → 63.5 MB，定位到 3 份 4K 壁纸；
- **24 h 抓包 + 截至 20:00 连接名单**（域名/IP/异常直连 DNS）；
- **两份设计稿**：静默安装（Session API + 白名单）、真息屏（先锁后 sleep）；
- 输入法：Gboard 装上并设默认；Vosk 离线语音做过 demo（非输入法），已按需卸载。

---

## 9. 拿到 ADB 之后能做什么

| 能力 | 一句话 | 文档 |
|---|---|---|
| 平台签名提权 | `platform.pk8` + 18 项 signature 权限 | `2026-11-22-c10h-launcher-platform-privilege-install.md` |
| 自研桌面接管 HOME | `com.c10h.launcher` | `dist/C10hLauncher/` |
| 裁掉厂商组件 | 29 个包禁用（可逆） | `scripts/disable_oem_apps.py` |
| 原厂商店清单 | 32 个 App | `.asess/2026-11-24-c10h-conn-list-until-2000.md` |
| 流量分析 | 24 h 抓包 + 名单 | 同上 |
| 恢复出厂重建 | 6 步 | `2026-11-22-c10h-bootloop-and-factory-reset-recovery.md` |
| 息屏 / 静默安装 | 两份设计 | `.asess/2026-11-24-launcher-*.md` |

---

## 附录 A · 三个最小服务（可直接抄）

### A.1 `apk_server.py`（假商店）
```python
import glob, os
from http.server import BaseHTTPRequestHandler, HTTPServer

BASE = os.path.dirname(os.path.abspath(__file__))
APK_DIR = os.path.join(BASE, "apk")

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        apks = sorted(glob.glob(os.path.join(APK_DIR, "*.apk")))
        if not apks:
            self.send_response(404); self.end_headers(); return
        data = open(apks[0], "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def log_message(self, *a):
        pass

if __name__ == "__main__":
    print("[apk_server] :6060 root=%s" % APK_DIR, flush=True)
    HTTPServer(("0.0.0.0", 6060), H).serve_forever()
```

### A.2 `cmd_server.py`（指令下发 + 回传）
```python
import http.server, json, pathlib, socketserver, time

ROOT = pathlib.Path(__file__).resolve().parent
REPORT = ROOT / "helper_report.log"

class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT), **kw)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        data = self.rfile.read(n) if n else b"{}"
        json.loads(data)
        with open(REPORT, "ab") as f:
            f.write(("[" + time.strftime("%m-%d %H:%M:%S") + "] ").encode() + data + b"\n")
        self.send_response(200); self.send_header("Content-Length", "2")
        self.end_headers(); self.wfile.write(b"ok")
    def log_message(self, *a):
        pass

socketserver.ThreadingTCPServer.allow_reuse_address = True
class S(socketserver.ThreadingTCPServer):
    daemon_threads = True

if __name__ == "__main__":
    print("[cmd_server] listening 10.10.0.1:8000", flush=True)
    S(("10.10.0.1", 8000), H).serve_forever()
```

### A.3 `dns_hijack.py`（Windows/macOS 无 dnsmasq 时用）
```python
import socket, struct

TARGET = b"appmarket.zhongping.jsinfo.net"
MYIP = socket.inet_aton("10.10.0.1")

def name_of(q, i):
    parts = []
    while q[i]:
        n = q[i]; parts.append(q[i+1:i+1+n]); i += n + 1
    return b".".join(parts).lower(), i + 1

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", 53))
print("[dns] :53 hijack ready", flush=True)
while True:
    data, addr = s.recvfrom(512)
    try:
        qname, off = name_of(data, 12)
        qtype = struct.unpack("!H", data[off:off+2])[0]
        ip = MYIP if (qname == TARGET and qtype == 1) else socket.inet_aton("0.0.0.0")
        resp = data[:2] + b"\x81\x80" + data[4:6] + b"\x00\x01\x00\x01\x00\x00\x00\x00"
        resp += data[12:off+4] + b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, 4) + ip
        s.sendto(resp, addr)
    except Exception:
        pass
```

---

## 附录 B · 安全提醒

- `service.adb.root 1` + `ro.debuggable=1` ⇒ **5555 是常驻 root shell**，只应存在于 `10.10.0.0/24` 隔离网段。
- 平台私钥（`platform.pk8`）不要提交到公开仓库。
- 拿到 root 后建议：改默认桌面 → 禁用厂商上报 → 固化 `adbauth.txt` 与 init 自启 → 再谈日常使用。
