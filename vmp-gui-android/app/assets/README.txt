此目录用于存放 CI 交叉编译生成的 vmpacker_arm64 二进制（见 .github/workflows/build-android.yml）。

本地如需手动构建：
  bash scripts/build_stub_linux.sh
  CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o app/assets/vmpacker_arm64 ./cmd/vmpacker/
