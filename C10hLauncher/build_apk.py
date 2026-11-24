# -*- coding: utf-8 -*-
"""
C10hLauncher APK 构建脚本（无 Gradle 依赖，纯 SDK 工具链）

流程: aapt2 compile/link -> javac -> jar -> d8 -> 追加 classes.dex -> zipalign -> apksigner 签名

产物: 项目根目录 C10hLauncher.apk（platform 平台钥签名，可 adb install 直装）
密钥: 项目根目录 platform.pk8 + platform.x509.pem（AOSP 公开 testkey，
      与本机平台钥同指纹 c8a2e9…92ab8；由 fetch_platform_key.py 获取校验。
      debug.keystore 已退役，仅作回退备份保留。）

说明: 本脚本由 C10hHelper/build_apk.py 复制改造，仅改动
      1) 删除测试变体(下载地址替换)逻辑  2) 产物名改为 C10hLauncher.apk  3) 文件头注释
      SDK / JDK 路径与原脚本保持一致，未改动。
"""
import os
import shutil
import subprocess
import sys
import zipfile

SDK = r"D:\AndroidStudioSDK"
BT = os.path.join(SDK, "build-tools", "36.0.0")
AJAR = os.path.join(SDK, "platforms", "android-36", "android.jar")
JDK = r"D:\jdk\jdk-17.0.3+7"

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "app", "src", "main")
BUILD = os.path.join(ROOT, "build")

env = os.environ.copy()
env["JAVA_HOME"] = JDK
env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")

AAPT2 = os.path.join(BT, "aapt2.exe")
D8 = os.path.join(BT, "d8.bat")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER = os.path.join(BT, "apksigner.bat")
JAVAC = os.path.join(JDK, "bin", "javac.exe")
JAR = os.path.join(JDK, "bin", "jar.exe")
KEYTOOL = os.path.join(JDK, "bin", "keytool.exe")


def run(cmd):
    print("+ " + " ".join(cmd))
    r = subprocess.run(cmd, env=env, capture_output=True, text=True,
                       errors="replace", cwd=ROOT)
    if r.stdout:
        print(r.stdout)
    if r.stderr:
        print(r.stderr, file=sys.stderr)
    if r.returncode != 0:
        raise SystemExit("FAILED (%d): %s" % (r.returncode, " ".join(cmd)))
    return r


def main():
    # 0) 清理（保留被模拟器占用的日志文件，避免 Windows 锁导致构建失败）
    if os.path.isdir(BUILD):
        for name in os.listdir(BUILD):
            p = os.path.join(BUILD, name)
            if name == "emulator5556.log":
                continue
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
            else:
                try:
                    os.remove(p)
                except OSError:
                    pass
    for d in ("gen", "classes", "dex"):
        os.makedirs(os.path.join(BUILD, d))
    main_dir = APP

    # 1) 平台签名钥（AOSP 公开 testkey，评审 S1：与本机平台钥同指纹）
    #    debug.keystore 退役，仅作回退备份保留：若需回退 debug 签名，恢复
    #    apksigner --ks debug.keystore --ks-pass pass:android 分支即可
    #    （代价：六项 signature|privileged 权限全部失授，功能进入红字降级态）。
    ks_pk8 = os.path.join(ROOT, "platform.pk8")
    ks_pem = os.path.join(ROOT, "platform.x509.pem")
    if not (os.path.exists(ks_pk8) and os.path.exists(ks_pem)):
        raise SystemExit("缺少 platform.pk8/platform.x509.pem，先运行 fetch_platform_key.py")

    # 2) aapt2 编译资源
    reszip = os.path.join(BUILD, "res.zip")
    run([AAPT2, "compile", "--dir", os.path.join(main_dir, "res"), "-o", reszip])

    # 3) aapt2 链接生成未签名 APK + R.java（minSdk 21 / targetSdk 28 在此注入）
    unsigned = os.path.join(BUILD, "unsigned.apk")
    # assets（模块 G 种子规则；目录不存在时跳过，保持旧行为）
    assets_dir = os.path.join(main_dir, "assets")
    extra = ["-A", assets_dir] if os.path.isdir(assets_dir) else []
    run([AAPT2, "link",
         "-o", unsigned,
         "-I", AJAR] + extra + [
         "--manifest", os.path.join(main_dir, "AndroidManifest.xml"),
         "--java", os.path.join(BUILD, "gen"),
         "--min-sdk-version", "21",
         "--target-sdk-version", "28",
         reszip])

    # 4) javac 编译（Java 8 字节码，兼容 minSdk 21 + d8）
    sources = []
    for base in (os.path.join(main_dir, "java"), os.path.join(BUILD, "gen")):
        for dp, _, fns in os.walk(base):
            sources.extend(os.path.join(dp, f) for f in fns if f.endswith(".java"))
    run([JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", AJAR, "-classpath", AJAR,
         "-d", os.path.join(BUILD, "classes")] + sources)

    # 5) 打 class 包 -> d8 转 dex（min-api 21）
    run([JAR, "cf", os.path.join(BUILD, "classes.jar"),
         "-C", os.path.join(BUILD, "classes"), "."])
    run([D8, "--release", "--min-api", "21", "--lib", AJAR,
         os.path.join(BUILD, "classes.jar"),
         "--output", os.path.join(BUILD, "dex")])

    # 6) 把 classes.dex 追加进 APK
    withdex = os.path.join(BUILD, "app-with-dex.apk")
    shutil.copyfile(unsigned, withdex)
    with zipfile.ZipFile(withdex, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(BUILD, "dex", "classes.dex"), "classes.dex")

    # 7) zipalign（签名前对齐）
    aligned = os.path.join(BUILD, "aligned.apk")
    run([ZIPALIGN, "-f", "4", withdex, aligned])

    # 8) apksigner 签名（v1+v2，平台钥 --key/--cert）
    out = os.path.join(ROOT, "C10hLauncher.apk")
    run([APKSIGNER, "sign",
         "--key", ks_pk8,
         "--cert", ks_pem,
         "--out", out,
         aligned])
    run([APKSIGNER, "verify", "--print-certs", out])

    print("OK: %s (%d bytes)" % (out, os.path.getsize(out)))


if __name__ == "__main__":
    main()
