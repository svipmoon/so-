此目录(app/src/main/assets)用于存放 CI 交叉编译生成的 vmpacker_arm64 二进制，会随 APK 打包。

本地如需手动构建：
  bash scripts/build_stub_linux.sh
  CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o app/src/main/assets/vmpacker_arm64 ./cmd/vmpacker/

（注意：vmpacker_arm64 必须位于 app/src/main/assets/ 才会被打进 APK）