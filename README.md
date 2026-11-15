# blbl-android

一个第三方哔哩哔哩安卓 App，支持触摸、遥控，以及安卓5，适用于平板、TV、车机等设备。


## 界面预览

**推荐页**
![推荐页](./example-pic/推荐页.png)

**分类页**
![分类页](./example-pic/分类页.png)

**动态页**
![动态页](./example-pic/动态页.png)

**直播页**
![直播页](./example-pic/直播页.png)

**我的页**
![我的页](./example-pic/我的页.png)

**搜索页**
![搜索页](./example-pic/搜索页.png)

**追番**
![追番](./example-pic/追番.png)

**视频播放页**
![视频播放页](./example-pic/视频播放页.png)

## 功能概览

- 侧边栏导航：搜索 / 推荐 / 分类 / 动态 / 直播 / 我的
- 扫码登录入口
- 视频播放：Media3(ExoPlayer)，支持分辨率/编码/倍速/字幕/弹幕等设置
- 设置页：播放与弹幕偏好等

## 技术栈

- Kotlin + AndroidX + ViewBinding
- Media3(ExoPlayer)/[Ijkplayer](https://github.com/cat3399/ijkplayer)
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

## API19 版本与更新

- `Android API19 Build` 默认从 `CHANGELOG.md` 第一条 `x.y.z` 版本生成 `x.y.z-api19-kitkat`，版本号按 `major * 1,000,000 + minor * 1,000 + patch` 计算。
- API19 更新检查读取 `MTGA-Legacy/blbl` 的 `mtga-api19-main/CHANGELOG.md`。
- `Android API19 Release` 手动输入基础版本（例如 `0.1.30`），发布 tag `v0.1.30-api19`，并附带 `blbl-android-0.1.30-api19-release.apk`；应用正式更新按这个版本化 Release 下载。
- API19 Build 和 API19 Release 都必须配置签名 Secrets，且签名证书必须与设备上已安装的 APK 一致；工作流会拒绝发布未签名 APK。未签名 APK 在 Android 4.4 上会显示“软件包似乎无效”。
- API19 Release 同时更新公开预发布 `api19-latest` 下的 `blbl-api19-kitkat-latest.apk`，供更新器兜底下载；`Android API19 Build` 的同名资产用于持续构建预览，普通 Actions artifact 仍用于构建归档，下载可能需要登录 GitHub。

## GitHub Actions

仓库包含两套手动触发的工作流：

- Android Debug：手动输入 `version_name`
- Android Release：同上，额外需要签名 Secrets

需要在仓库 Secrets 中配置：

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## 感谢

- https://github.com/SocialSisterYi/bilibili-API-collect B站API收集整理
- https://github.com/xiaye13579/BBLL 优秀的页面设计和操作逻辑，本项目绝大部分页面和操作逻辑都是抄袭BBLL🥰
- https://github.com/bggRGjQaUbCoE/PiliPlus 部分关键功能参考了Piliplus的逻辑
- https://github.com/debugly/ijkplayer 感谢debugly大佬移植的ijkplayer
- 开源第三方B站客户端
- 群友们的详细测试与反馈

## 免责声明

> 不得利用本项目进行任何非法活动。 不得干扰B站的正常运营。 不得传播恶意软件或病毒。 此外，为降低法律风险

1. 🚫禁止在官方平台（b站）及官方账号区域（如b站微博评论区）宣传本项目
2. 🚫禁止在微信公众号平台宣传本项目
3. 🚫禁止利用本项目牟利，本项目无任何盈利行为，第三方盈利与本项目无关

代码都是codex写的，如有问题请联系https://openai.com/ 😤
