# Axolotl 课表

原生 Android / Kotlin / Jetpack Compose 课表。支持 Android 9+，主要在 API 35 真机验收。

## 使用

打开即显示本周课表。在右上角设置中输入教务系统学号、密码和学期，例如 `202601`，点击“登录并获取课表”。账号密码按私人使用需求直接存放在应用私有 `noBackupFilesDir`，课表和外观设置分别保存，不上传到其他服务。

- 点击顶部“第 X 周”打开周次选择器；圆点按七天 × 节次排列，有课着色。面板中可回到本周。
- 左右滑动切周，课表不纵向滚动，按可用高度显示全部节次。长课程名点开查看完整内容。
- 默认显示周一至周五。当周有周末课程时自动展开，左上角可手动展开/收起。设置里的周末排除只隐藏指定课程的周末排课，不影响工作日，也不删除数据。
- 默认作息为第 1～10 节，末尾另有 `* 21:20～end` 空行。可在设置修改和增加节次；接口中的额外节次不会丢弃。
- 首次开学日期只能估算，请在“学期与作息”按校历设置第一周周一。
- 默认使用系统莫奈取色（Android 12+），设置中可改用自定义主题色；课程颜色和 emoji 可单独配置。
- 支持自选背景图片及透明度，0% 完全显示图片，100% 隐藏图片。
- 先读取本地快照，进入前台后尝试刷新。自动失败不提示；手动刷新显示结果。清除登录信息后仍保留离线课表。

## 实现

单 Activity + ViewModel + StateFlow + Repository。OkHttp 完成首页 Cookie 预热、Base64 密码表单登录和课表请求；会话过期重登重试一次。Kotlin JSON 解析保留原始字段，支持节次前导零、周范围、单双周、排课去重和冲突详情。成功解析并原子保存后才替换课表和成功时间。

接口协议参考范围仅限用户指定的 `timetable-standalone` 子目录。未读取该参考项目其他目录。

## 构建与验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

设备测试使用测试夹具，不访问真实教务账号。学校 HTTPS 连通性和当前账号数据仍需要首次登录验收。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。Release 默认启用 R8 优化，产物为未签名 APK；发布前需配置自己的签名。

本次设备验收另生成了 `app/build/outputs/apk/release/app-release.apk`，使用本机开发签名，已覆盖安装并保留在设备上，大小约 2.9 MB。

## 本次验收结果（2026-10-12）

- 31 项单元测试通过；API 35 真机的 14 项界面、缓存及测试辅助用例通过。
- Debug / Release 构建通过，Lint 为 0 错误；6 项提示均为依赖有更新版本。
- 已检查整页课表、周次圆点矩阵、课程详情、设置及小尺寸大字体的截图。
- 优化版使用已有缓存的三次冷启动 `am start -S -W` 为 586 / 600 / 590 ms；一次 `reportFullyDrawn` 记录为 662 ms。该标记等待本地课表就绪，背景图片独立加载，不包含学校网络刷新。
- 启动辅助夹具只在显式调用时写入，且不覆盖已有课表；测量后已执行清理。设备上的个人课表、背景与设置保留。
- 学校协议通过本地模拟服务器测试；未在测试代码或仓库中保存真实账号密码。
