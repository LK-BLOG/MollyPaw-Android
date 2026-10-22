# MollyPaw 远程（Android）

手机端。用手机连上家里/公司电脑上的 MollyPaw，让它替你干活。

## 它能干什么

- **聊天** — 逐字返回，跟电脑上一样
- **审批** — 电脑上的 AI 想执行危险操作时，手机上弹窗让你点头或拒绝
- **会话** — 新建、切换、删除，和电脑共用同一份历史
- **文件** — 浏览电脑上的目录、把文件拉到手机
- **停止** — 它开始乱来，一键掐停（真中断：断流 + 杀子进程 + 回滚）

## 怎么用

1. 电脑上打开 MollyPaw → 设置 → **远程互联** → 开启 → **添加设备**
   （会显示一个二维码和一段配对码）
2. 手机上装这个 App → 把配对码粘进去 → 完成
3. 之后直接用，不用再输密钥

**只需扫一次码。** 配对码 15 分钟有效、用一次就作废。

## 安全是怎么做的

- 电脑和手机之间的内容**全程端到端加密**（X25519 + HKDF + AES-256-GCM）
- 中继服务器（Cloudflare）**只转发不解析**，看不到你的聊天内容
- 配对码是一次性凭据，扫过就作废
- 手机端可设为**只读**（能看不能操作）

## 构建

```bash
# 需要 JDK 17+、Android SDK（compileSdk 35）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

离线构建（不联网下载依赖）：

```bash
python tools/setup_local_deps.py   # 把本机已有的 jar 放进本地 m2
./gradlew :app:assembleDebug --offline
```

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

`CryptoTest` 里有一组**跨实现一致性**测试：Android 算出的
room / pair_key / session_key 必须和 Python 端
（`MollyPaw Agent/agent/relay/crypto.py`）**逐字节相同**。
对不上就是两端实现分叉了，跨端一定连不上。

## 协议

协议规范在 `MollyPaw-Relay/PROTOCOL.md`，那是唯一真源。

## 许可

GPLv3 · 灵感来自摩尼，一只可乐色和白色的小泰迪犬
