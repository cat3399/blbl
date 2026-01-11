# blbl-android

第三方哔哩哔哩安卓 App，支持触摸、遥控，以及安卓5。

## 功能概览

- 侧边栏导航：搜索 / 推荐 / 分类 / 动态 / 我的
- 扫码登录入口
- 视频播放：Media3(ExoPlayer)，支持分辨率/编码/倍速/字幕/弹幕等设置
- 设置页：播放与弹幕偏好等

## 技术栈

- Kotlin + AndroidX + ViewBinding
- Media3(ExoPlayer)
- OkHttp
- Protobuf-lite
- Material / RecyclerView / ViewPager2

## 构建

环境要求：JDK 17，Android SDK（compileSdk 36）。

调试包：

```
./gradlew assembleDebug
```

发布包（已开启 R8 混淆 + 资源压缩）：

```
./gradlew assembleRelease
```

可选版本参数（本地或 CI）：

```
./gradlew assembleRelease -PversionName=0.1.1 -PversionCode=2
```

## 临时更新方案
**目前在代码中内置了国内环境可直接访问的直链,用于在测试阶段方便的覆盖更新,待后续稳定之后将会移除**,介意者请从release中下载action编译的安装包

## GitHub Actions

仓库包含两套手动触发的工作流：

- Android Debug：手动输入 `version_name`
- Android Release：同上，额外需要签名 Secrets

需要在仓库 Secrets 中配置：

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

