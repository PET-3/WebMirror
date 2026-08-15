# WebMirror

一个简洁的 Android 网站镜像下载工具，UI 参考 [Shizuku](https://github.com/RikkaApps/Shizuku) 的 Material Design 3 风格。

## 功能

- 输入任意网站 URL，按原目录结构递归下载页面、CSS、JS、图片等资源
- 可配置最大递归深度（0~5）
- 可选「仅同域名」过滤外链
- 实时进度显示与最近文件列表
- Material 3 + 动态取色（Android 12+）
- 文件保存在应用专属目录，无需额外存储权限（Android 10+）

## 界面预览

- 卡片式布局，圆角与 Shizuku 类似的干净风格
- 支持浅色 / 深色 / 动态颜色

## 构建

### 本地

```bash
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions

推送到 `main` / `master` 或手动触发 Workflow 即可自动编译 Debug APK，产物可在 Actions Artifacts 中下载。

## 技术栈

- Kotlin + Jetpack Compose
- Material 3
- OkHttp + Jsoup
- Coroutines + Flow
- minSdk 26 / targetSdk 35

## 注意

- 本工具仅供学习与个人备份使用，请遵守网站 robots.txt 与版权规定
- 深度过高或站点过大时可能耗时较长、占用较多存储
- 不保证对所有动态渲染（SPA）站点完整还原

## License

MIT
