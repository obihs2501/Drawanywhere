<div align="center">
  <img src="../SVG Icon/pen-new-square-original.svg" width="96"/>
</div>

<h1 align="center">DrawAnywhere</h1>

<div align="center">
  <a href="https://f-droid.org/packages/com.shezik.drawanywhere/">
    <img src="https://img.shields.io/f-droid/v/com.shezik.drawanywhere" alt="Get it on F-Droid"/>
  </a>
  &nbsp;
  <a href="https://shields.rbtlog.dev/com.shezik.drawanywhere">
    <img src="https://shields.rbtlog.dev/simple/builders/com.shezik.drawanywhere" alt="RB shield"/>
  </a>
</div>

&emsp;[English](README.md) | 中文

DrawAnywhere 是一个可以让你在屏幕任意地方上绘图的安卓软件。

![](../metadata/zh-CN/images/featureGraphic.png)

也可以在 [Releases 页面](https://github.com/DrawAnywhere/DrawAnywhere/releases/latest) 下载最新 APK。

## 🆕 自 v1.2 以来的更新

经过了 7 个月的沉默，DrawAnywhere 回来了。来看看都变了什么：

- **无限画布与锁定** — 自由平移和缩放；三档锁定（无、仅缩放、全部），工具栏和 HUD 均有图标
- **新工具** — 矩形、椭圆、激光笔（glow 发光 + 自动消失）、像素橡皮
- **颜色系统** — HSV 色轮、18 格预设色板、最近使用颜色、Hex 代码输入
- **多指手势** — 捏合缩放；双击重置缩放、三击重置位置（双指）；切换透传或隐藏画布（三指）
- **画笔悬浮预览** — 带填充的笔刷大小预览圆圈，颜色跟随笔配置，离开后平滑淡出
- **再次打开即关闭** — 服务运行时打开软件会直接关闭它
- **拖拽关闭** — 拖拽工具栏到屏幕底部即可关闭软件
- **架构重写** — 混合 Native Canvas + Jetpack Compose 工具栏；更清晰的触控派发、撤销/重做和输入路由

## 🤖 关于 AI 使用的说明
从 v2.0 开始，项目中引入了 AI 生成内容（AIGC）。由于项目的可持续性与个人生活之间的平衡变得有些难以维持，才最终作出了这一决定。不过，我们仍致力于提供良好的稳定性和易用性，同时保持代码质量。与往常一样，如有任何 bug 报告或功能请求，请随时提交，我们一起处理。

## 🎨 功能

**绘画工具**
- 自由画笔、矩形和椭圆工具
- 3 秒后自动消失的激光笔
- 像素橡皮与可通过手写笔按钮激活的笔画橡皮
- 最多 50 步的撤销和重做
- 取色器：HSV 色轮、预设色板、最近使用颜色和 Hex 输入
- 笔触宽度和不透明度滑块

**画布与手势**
- 无限画布，双指捏合缩放
- 双指双击：重置缩放
- 双指三击：重置画布位置
- 三指双击：切换触摸透传
- 三指三击：隐藏画布
- 三档画布锁定（无锁定 / 缩放锁定 / 全部锁定），通过工具栏按钮切换

**工具栏**
- 支持横向和竖向，可长按拖动到屏幕任意位置
- 空闲 3 秒后半透明化
- 一键隐藏画布，或让触摸事件穿透到下方应用
- 手指绘制开关，可设为纯手写笔模式

**便利功能**
- Quick Settings 磁贴一键开关绘制服务，再次点击关闭
- 服务运行时再次打开软件会直接关闭，将工具栏拖到屏幕底部也可关闭软件
- 笔刷大小预览圆圈，手指和笔均支持

## ✨ 使用教程
- **移动工具栏** — 长按并拖动到任意位置。
- **锁定视图** — 点击工具栏上的锁定按钮，HUD 会显示当前锁定状态：🔓 解锁 → 🔒🔍 缩放锁定 → 🔒 全部锁定。
- **精准绘制** — 双指捏合缩放画布；双指双击回到 100% 大小；双指三击重新居中画布。
- **清空与透传** — 在设置中启用"隐藏画布时清空"，画布隐藏时自动擦除内容（透传也会一并关闭）。三指双击在绘制中随时切换透传。
- **拖拽关闭** — 将工具栏拖到屏幕底部即可关闭软件。
- **快速启动** — 如果你偏好默认隐藏画布，可以在设置中关闭"启动时显示画布"。

## 🖊️ 小米平板手写笔与保存（hpos 分支）

**小米焦点触控笔（二代 / Pro）笔身手势**
- 在「设置 → 手写笔设置 → 手写笔按键方案」选择「小米焦点触控笔」，即可分别为 **轻捏、连击（快速轻捏两次）、上滑、下滑** 指定动作（切换颜色、橡皮、撤销、粗细等）。映射只在本应用内生效，不会读写系统的手写笔设置。HyperOS 把连击作为两次轻捏下发，应用内按「连击间隔」（默认 400 毫秒）识别；把连击设为「无操作」则单次轻捏即时触发。
- 系统侧只需设置一次：「设置 → 手写笔 → 轻捏」选「快捷轮盘」以外的任一项（如「切换橡皮」）。选快捷轮盘时 HyperOS 会自己处理轻捏、不下发给应用；选其他项时具体动作由本应用的映射决定。
- 原理：画布显示期间，应用直接通过 Binder 向 HyperOS 手写笔服务（`com.xiaomi.touchservice`，`IPencilEngine.registerListener(包名)` + `setEnable(2, 1)`）登记并启用「触控按键」模式，系统随后把笔身手势作为 KeyEvent（轻捏 194 / scanCode 189，连击 195，上滑 196，下滑 197）送到画布窗口。画布隐藏或开启透传时会自动解除登记，把手势还给系统。无需小米开发中心的 App ID；也可在设置里切换为随包的 PenEngine SDK `init()` 路径。
- 「系统握手状态」一行显示连接与 `setEnable` 返回值；「诊断」面板记录画布收到的每个按键事件，可一键复制；「打开手势测试页」可在独立 Activity 中验证按键送达。

**屏幕背景保存**
- 「记住屏幕截图授权」开启时，同一次运行内只需同意一次「允许应用投射」；Android 14+ 的授权只能用一次，所以应用会持有截图会话（持续镜像到离屏缓冲，只在导出时取一帧）直到服务停止，期间状态栏会有投屏图标。Android 14+ 上授权对话框只提供「整个屏幕」。
- 导出前会隐藏整个控制板 / 设置窗口（含窗口背景与模糊），截图按画布在屏幕上的位置 1:1 裁剪。
- 已 root 的设备可开启「Root 截屏」，直接用 `su screencap` 取图，完全不弹授权；失败时自动回退。

## 💌 感谢
[Akshay Sharma](https://github.com/akshay2211) 的 [DrawBox](https://github.com/akshay2211/DrawBox) 作为灵感来源，<br>
[480 Design](https://www.figma.com/@480design) 和 [R4IN80W](https://www.figma.com/@voidrainbow) 的 [Solar Icons Set](https://www.figma.com/community/file/1166831539721848736/solar-icons-set) 提供的漂亮得惊人的软件图标（[CC BY 4.0](../SVG%20Icon/LICENSE.md)），<br>
以及 [Mauro Banze](https://stackoverflow.com/a/66958772) 和 [Yannick](https://stackoverflow.com/a/65760080) 的 Stack Overflow 回答（[CC BY-SA 4.0](../app/src/main/java/com/shezik/drawanywhere/view/ToolbarLifecycleOwner.kt)）！

最后，感谢[您](https://play.google.com/store/apps/details?id=com.kts.draw)把自己的软件变成订阅制付费模式！你是我最初的动力！[^1]

[^1]: 虽然占了很大一部分因素，但其实并不完全是这样。另一部分原因是运行 OneUI 4 的平板不自带这种功能。如果你像我一样还在用 OneUI 4，一定要来看看[Wallpaper Setter](https://github.com/shezik/WallpaperSetter)!
