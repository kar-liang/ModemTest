#!/bin/sh
# 手动解压 SDK 组件（不用 sdkmanager，避免交互卡死）+ 编译 ModemTest APK
echo "SCRIPT_STARTED $(date)"
SDK=/sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-30/android.jar

echo "=== 1) 解压 platform 30 / build-tools 34 / platform-tools（jar 解压，镜像无 unzip）==="
cd /tmp
rm -rf ex && mkdir -p ex && cd ex
jar xf /x/platform-30_r03.zip
jar xf /x/build-tools_r34-linux.zip
jar xf /x/platform-tools_r37.0.1-linux.zip
echo "   解出目录: $(ls -d /tmp/ex/* 2>/dev/null | tr '\n' ' ')"

mkdir -p $SDK/platforms $SDK/build-tools
for d in /tmp/ex/android-30 /tmp/ex/android-11; do
  [ -d "$d" ] && cp -r "$d" $SDK/platforms/android-30
done
[ -d /tmp/ex/android-14 ] && cp -r /tmp/ex/android-14 $SDK/build-tools/34.0.0
[ -d /tmp/ex/platform-tools ] && cp -r /tmp/ex/platform-tools $SDK/platform-tools
chmod -R +x $SDK/build-tools $SDK/platform-tools $SDK/platforms 2>/dev/null
echo "--- 组件确认 ---"
ls $SDK/platforms $SDK/build-tools 2>&1
ls -la $PLAT 2>/dev/null || echo "X 找不到 android.jar"

PKG=/x/out
rm -rf $PKG && mkdir -p $PKG /x/gen /x/classes

echo "=== 2) aapt：打包资源 + 生成 R.java ==="
$BT/aapt package -f -m -J /x/gen -M /x/AndroidManifest.xml -S /x/res -I $PLAT
echo "   aapt exit=$?"

echo "=== 3) javac 编译 ==="
find /x/gen /x/src -name '*.java' 2>/dev/null > /x/sources.txt
echo "   源文件: $(wc -l < /x/sources.txt) 个"
javac -nowarn -encoding UTF-8 -source 8 -target 8 \
  -cp "$PLAT" -d /x/classes @/x/sources.txt 2>&1 | head -25
echo "   class: $(find /x/classes -name '*.class' | wc -l) 个"

echo "=== 4) d8 dex ==="
$BT/d8 --min-api 26 --release --output $PKG $(find /x/classes -name '*.class')
echo "   dex exit=$? 文件: $(ls $PKG 2>/dev/null)"

echo "=== 5) 打包 + 签名 ==="
$BT/aapt package -f -M /x/AndroidManifest.xml -S /x/res -I $PLAT -F $PKG/unsigned.apk
echo "   aapt package exit=$?"
# 用 aapt add 加 dex（不要用 jar uf：它会重写 zip 头，破坏 arsc 的 4 字节对齐）
cd $PKG
$BT/aapt add -f unsigned.apk classes.dex
echo "   aapt add exit=$?"
cd /tmp

KS=/x/debug.keystore
if [ ! -f $KS ]; then
  keytool -genkeypair -v -keystore $KS -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" 2>&1 | tail -2
fi
$BT/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android \
  --out $PKG/ModemTest.apk $PKG/unsigned.apk
echo "   签名 exit=$?"

echo "=== 6) 验证 ==="
$BT/apksigner verify --print-certs $PKG/ModemTest.apk | head -3
$BT/aapt dump badging $PKG/ModemTest.apk 2>/dev/null | grep -E "^package|launchable-activity" | head -3
$BT/aapt dump badging $PKG/ModemTest.apk 2>/dev/null | grep -c "uses-permission" | sed 's/^/   权限条数: /'
echo "   ModemTest.apk = $(stat -c %s $PKG/ModemTest.apk 2>/dev/null) 字节"
echo "BUILD_OK" > /x/build_ok.txt
chmod -R 755 /x/out /x/gen /x/classes 2>/dev/null
chmod 644 /x/build_ok.txt /x/build_log.txt 2>/dev/null
