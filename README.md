# WebMirror

一个简洁的 Android 网站镜像下载工具，UI 参考 [Shizuku](https://github.com/RikkaApps/Shizuku) 的 Material Design 3 风格。行为上对齐 HTTrack 的核心体验：递归抓取 + 下载完成后链接重写为相对路径，便于离线浏览。

## 功能

- 输入任意网站 URL，按原目录结构递归下载页面、CSS、JS、图片等资源
- **离线链接重写**（HTTrack 风格）：全部下载完成后，把 HTML/CSS 内站内链接改成相对路径
- 可配置最大递归深度与并发数
- 可选「仅同域名」过滤外链、遵守 robots.txt
- 实时进度显示
- Material 3 + 动态取色（Android 12+）
- 文件写入应用专属目录（无需额外存储权限）；可选完成后导出到用户选择的外部目录（SAF）

## 文件保存在哪里

- **工作目录（真实下载位置）**：`Android/data/<包名>/files/Documents/WebMirror/`
- 若在设置里「选择导出目录」，镜像完成后会自动把整个目录树复制到该位置

用电脑浏览时：把工作目录或导出目录拷到电脑，进入对应站点子目录后执行：

```bash
python3 -m http.server 8000
```

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
- Coroutines + Flow + Room
- minSdk 26 / targetSdk 35

## 注意

- 本工具仅供学习与个人备份使用，请遵守网站 robots.txt 与版权规定
- 深度过高或站点过大时可能耗时较长、占用较多存储
- 不保证对所有动态渲染（SPA）站点完整还原

## License

MIT
