此目录(app/src/main/engineLibs/arm64-v8a)用于存放 CI 交叉编译生成的 vmpacker 引擎二进制，
以 native lib 方式打入 APK（lib/arm64-v8a/libvmpacker_arm64.so）。

系统会以「只读 + 可执行」方式提取 nativeLibraryDir，符合 Android 10+ W^X 安全策略，
这是 App 内执行内置二进制的标准方式。

（文件由 .github/workflows/build-android.yml 在 CI 构建时自动生成，无需手工放置）