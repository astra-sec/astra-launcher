# Astra Sec 正式签名

Astra Launcher 与 Astra TV 使用同一份 Astra Sec 签名证书，主体为 `CN=Astra Sec, O=Astra Sec`。证书 SHA-256：

```text
4a07274dddebcd69a6107b0e3bd7f0da1499bbc74d00b9c02abb1cdc88e8daf1
```

## 本地配置

私钥和密码均保留在仓库外：`~/.android/astra-sec/astra-sec-release.p12` 和 `~/.android/astra-sec/signing.properties`。不要提交私钥、配置文件或它们的备份。私密目录权限应为 `700`，文件权限应为 `600`。

配置使用四个键：`ASTRA_SIGNING_STORE_FILE`、`ASTRA_SIGNING_STORE_PASSWORD`、`ASTRA_SIGNING_KEY_ALIAS`、`ASTRA_SIGNING_KEY_PASSWORD`。可用 `-PastraSigningProperties=/absolute/path/signing.properties` 指定其他配置位置。如果设置 `ASTRA_SIGNING_STORE_FILE` 环境变量，则全部四个值从环境变量读取，不混用本地配置。

```sh
./gradlew --no-configuration-cache assembleRelease testDebugUnitTest lintRelease
```

Release 缺少配置时明确失败，不回退到 Debug 签名。只检查源码时，可以显式使用 `-PastraUnsigned=true` 生成不能直接安装的未签名 Release。Debug 仍使用开发签名。默认关闭 configuration cache，避免签名配置进入配置缓存；不要为签名构建开启它。

## GitHub Actions

此仓库需要四个 Actions Secrets：

- `ASTRA_SIGNING_KEYSTORE_BASE64`：PKCS12 私钥文件的 Base64 编码。
- `ASTRA_SIGNING_STORE_PASSWORD`：keystore 密码。
- `ASTRA_SIGNING_KEY_ALIAS`：密钥别名。
- `ASTRA_SIGNING_KEY_PASSWORD`：私钥密码。

Secrets 必须单独配置到 `astra-sec/astra-launcher`；其他仓库已经配置不代表本仓库可用。Base64 不是加密，编码值同样是秘密，不要放入命令参数或日志。

只有非 PR 的 `main` 构建使用这些 Secrets。密钥仅还原到 runner 临时目录，退出时删除；产物只上传 APK、混淆映射和测试/Lint 报告，不上传 keystore 或 Gradle 配置缓存。APK 在上传前通过 `apksigner verify` 并核对上述证书指纹。所有 PR（包括本仓库 PR）和其他分支只构建 Debug APK。

拥有主分支代码修改权限的维护者处于签名密钥的信任范围内。请保护主分支和工作流修改权限。共享证书也意味着密钥泄露会影响所有使用该证书的应用，应将私钥备份到独立加密存储，密码另存密码管理器；GitHub Secrets 不能作为唯一备份。

## 安装与恢复

正式包不能直接覆盖旧 Debug 签名安装。首次迁移前记录或导出设置，卸载旧版后安装正式包；卸载会清除应用数据。后续本地和 CI 正式包使用相同证书，满足 Android 版本号规则时可以覆盖升级。

换电脑时恢复原私钥和配置并修正路径，不要重新生成密钥。Astra TV 和 Astra Launcher 仍是不同包名的独立应用，共用证书不会合并应用或数据。
