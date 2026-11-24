# -*- coding: utf-8 -*-
"""
C10hHelper APK 构建脚本（无 Gradle 依赖，纯 SDK 工具链）

流程: aapt2 compile/link -> javac -> jar -> d8 -> 追加 classes.dex -> zipalign -> apksigner 签名

产物: 项目根目录 C10hHelper.apk（debug 签名，可直接安装测试）
密钥: 项目根目录 debug.keystore（首次运行自动生成, storepass/keypass = android）
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

# 生产下载地址（与 MainActivity.java 中硬编码一致）
PROD_DOWNLOAD_URL = "http://10.10.0.1:8000/factorytest_config.enc"


def prepare_main_dir():
    """默认返回工程 app/src/main。

    设置环境变量 C10H_DOWNLOAD_URL 时，复制一份源码并把下载地址替换为
    该值（仅用于本机联调测试，如模拟器指向 10.0.2.2），不影响生产源码。
    """
    override = os.environ.get("C10H_DOWNLOAD_URL")
    if not override:
        return APP
    dst = os.path.join(BUILD, "test-src")
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(APP, dst)
    java = os.path.join(dst, "java", "com", "c10h", "helper", "MainActivity.java")
    with open(java, "r", encoding="utf-8") as f:
        text = f.read()
    assert PROD_DOWNLOAD_URL in text, "未找到生产下载地址常量，无法替换"
    text = text.replace(PROD_DOWNLOAD_URL, override)
    with open(java, "w", encoding="utf-8") as f:
        f.write(text)
    print("[test variant] 下载地址替换为: " + override)
    return dst

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
    # 0) 清理（先清理，prepare_main_dir 会把测试副本写进 build）
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    for d in ("gen", "classes", "dex"):
        os.makedirs(os.path.join(BUILD, d))
    main_dir = prepare_main_dir()

    # 1) 调试密钥库（不存在则生成）
    ks = os.path.join(ROOT, "debug.keystore")
    if not os.path.exists(ks):
        run([KEYTOOL, "-genkeypair", "-v",
             "-keystore", ks,
             "-alias", "androiddebugkey",
             "-storepass", "android",
             "-keypass", "android",
             "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000",
             "-dname", "CN=Android Debug,O=Android,C=US"])

    # 2) aapt2 编译资源
    reszip = os.path.join(BUILD, "res.zip")
    run([AAPT2, "compile", "--dir", os.path.join(main_dir, "res"), "-o", reszip])

    # 3) aapt2 链接生成未签名 APK + R.java（minSdk 21 / targetSdk 28 在此注入）
    unsigned = os.path.join(BUILD, "unsigned.apk")
    run([AAPT2, "link",
         "-o", unsigned,
         "-I", AJAR,
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

    # 8) apksigner 签名（v1+v2）
    out = os.path.join(ROOT, "C10hHelper.apk")
    run([APKSIGNER, "sign",
         "--ks", ks,
         "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         "--out", out,
         aligned])
    run([APKSIGNER, "verify", "--print-certs", out])

    print("OK: %s (%d bytes)" % (out, os.path.getsize(out)))


if __name__ == "__main__":
    main()
