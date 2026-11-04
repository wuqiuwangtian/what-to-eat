# 吃什么

少一点纠结，好好吃这一顿。

一个本地优先的原生安卓饮食决策助手：点击“吃什么？”得到一份具体餐食，不满意就“换一个”。也可以看看已有餐食的搭配，或让它在两道菜之间给个建议。

**当前为开发预览，尚未完成正式发行验收。** 现有113条原型餐食与轻量规则，不宣称已优于随机或大模型。构建、设备和效果验证状态见[总体计划](docs/总体计划.md)与[验证记录](docs/验证与发布.md#本轮实际证据)。

已通过20项单元测试、4项Android 10模拟器设备测试及开发APK构建，Lint无错误。真机性能、真实模型服务与真人效果尚未验证。

[实际首页截图](docs/images/android-home.png) · [实际推荐截图](docs/images/android-recommendation.png)

## 当前能力

- 一键推荐、会话去重、明确原料限制、可选偏好学习与真实依据。
- “看看这顿”：本地文字匹配，核对菜品、准备吃/已经吃过及时间后保存，给一般性搭配建议。
- “帮我二选一”：从两个已知菜品中推荐，用户实际选择单独记录。
- 本地历史纠正、删除、导出；关闭学习、长期排除及清除全部本地数据。
- 可选自备API：OpenAI兼容文本接口、HTTPS、结构化结果校验、失败回退；不配置也能用。

没有账号、应用后端、自动遥测或主流程模型请求。普通推荐离线执行。典型配方标签不保证商家实际原料或过敏安全，不提供精确营养及疾病膳食方案。详见[隐私说明](PRIVACY.md)。

## 构建与运行

需要JDK17。`gradlew`会下载并校验固定的Gradle发行包，首次构建需要联网。

只检查核心，无需Android SDK：

```bash
./gradlew -PcoreOnly :core:test :experiments:run --console=plain
python3 scripts/check_repository.py
```

安卓：Android Studio打开本目录，使用JDK17，安装SDK Platform 35和Build Tools 35.0.0；通过`ANDROID_HOME`或本机`local.properties`指定SDK。最低Android 10（API29）。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

开发APK使用开发签名。首次打开直接点击“吃什么？”；在“看看这顿”输入“准备吃番茄炒蛋”，核对后保存为计划；在“记录”里应显示“打算吃”。

可选模型在“设置”填写HTTPS基础地址（含`/v1`）、模型名和自己的API Key，确认上传与费用说明。仅主动点击云端解析或对照时联网。首版尚不支持截图/OCR与端侧生成模型。

## 目录与参与

- `core/`：纯Kotlin推荐、餐食匹配、113条自编典型配方标签及行为测试。
- `app/`：Compose界面、Room事件、Keystore密钥与API适配器。
- `experiments/`：固定种子的合成情境检查；不是用户效果试验。
- [总体计划与状态](docs/总体计划.md)：有效范围、阶段门槛与下一项工作。
- [验证与发布](docs/验证与发布.md)：设备验收、研究协议和正式发行清单。
- [初版需求](docs/初版需求报告.md)、[研究资料](docs/研究与验证计划.md)、[同类项目调研](docs/GitHub开源项目调研.md)：早期背景及来源。
- [贡献指南](CONTRIBUTING.md)、[安全报告](SECURITY.md)、[变更记录](CHANGELOG.md)。

## 许可

本项目自有代码、文档与自行整理的餐食数据采用[MIT](LICENSE)。第三方组件保留其原许可，见[第三方说明](legal/THIRD_PARTY_NOTICES.md)。不分发真实饮食记录、第三方模型权重或复制的菜谱图片。
