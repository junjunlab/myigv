# myigv

> A customized IGV genome browser: offline annotations, multi-gene panels, GTF-based gene search, auto-indexing and per-panel autoscale.

**myigv** 基于 [IGV 2.19.8](https://github.com/igvteam/igv) 定制，保留 IGV 的全部能力，并针对
**离线使用**、**多基因对比**、**Hi-C 染色质构象**与 **SSH/SFTP 远程数据**四个高频场景做了增强。
适用于 RNA-seq / ChIP-seq / bigWig / Hi-C 等常见可视化，也能直接读取服务器上的文件。

---

## 相比原版 IGV 的改进

| # | 改进 | 一句话说明 |
|---|------|-----------|
| 1 | **注释离线** | 18 个 Hosted Genomes 的注释资源预打包，断网可用；参考序列仍按需联网 |
| 2 | **多基因分面** | 搜索框用逗号分隔多个基因，回车直接出多面板 |
| 3 | **GTF 作为搜索源** | 搜索框旁下拉选择 GTF/GFF，只按该文件里的基因名匹配 |
| 4 | **自动排序 + 建索引** | 导入 GTF/FASTA 自动排序、建索引，无弹窗、无需手动 |
| 5 | **规范基因名回填** | 搜索后回填基因名（不是坐标），并可继续追加基因 |
| 6 | **去掉 GTF 长实心条** | 过滤无外显子的 `gene` 容器记录，只剩真实转录本 |
| 7 | **分面级 Group Autoscale** | 多基因分面下每个面板独立缩放，互不挤压 |
| 8 | **Ctrl+滚轮缩放** | 以鼠标位置为锚点缩放，光标下的位点不动 |
| 9 | **轴标签可调** | Y 轴 `[min - max]` 标签的字号、左上/右上位置可调 |
| 10 | **一键安装包** | GitHub Actions 自动产出 Windows `.exe` / macOS `.dmg` |
| 11 | **压缩文件直接读取** | `.gtf.gz` / `.fa.gz` 自动解压一次，无需手动 gunzip |
| 12 | **转录本标签可切换** | 展开视图可显示基因名 / 转录本 ID / 两者 |
| 13 | **Hi-C 矩阵轨道** | 直读 HiCExplorer `.h5`、cooler `.cool` / `.mcool`、Juicer `.hic`（均支持 `.gz` 包裹），三角形或方形显示，多分辨率随缩放自动切换 |
| 14 | **TAD 轮廓叠加** | 把 BED 直接画在矩阵上，每个文件独立的线宽、线型、颜色、填充 |
| 15 | **BEDPE 弧线轨道** | loops / arcs 可视化，圆形、椭圆、贝塞尔三种弧型可选 |
| 16 | **SSH/SFTP 远程浏览** | 连接 SSH 服务器、浏览远程目录，直接读取其中的轨道（含 Hi-C）；凭据用系统级加密保存 |
| 17 | **BED 显示箭头** | Feature 轨道右键一键开关，按正负链画方向箭头；缺链信息时弹出提示 |

---

## 1. 注释离线，序列在线

打开 IGV 时原本需要联网下载的**注释类资源**已全部预打包进安装包（`pack/`，119 个资源，约 86 MB）：

- 18 个 Hosted Genomes 的基因组配置（`.json` / `.gbk`）；
- 染色体大小、cytoBand、别名、chromAlias 等辅助文件；
- 默认自动加载的注释轨道（如 hg38 的 RefSeq、MANE、rsdb 等）；
- 基因组下拉列表与 hub 索引（`genomes2.tsv`、`trackDb.txt` 等），让 Tracks 菜单离线可列目录。

**参考基因组序列**（`.fa` / `.2bit`）仍按 IGV 原逻辑联网按需获取——这是有意保留的行为。

实现上由 `LocalResourcePack` 在 I/O 边界把原始 URL 映射到本地文件，不改动 `ResourceLocator` 的原始 URL。

## 2. 多基因搜索 → 基因分面

搜索框输入 `JUN, JUND, FOS`（逗号或分号分隔）回车，直接进入 **gene list** 模式，每个基因一个面板。
坐标形式（如 `chr1:1000-2000`）不受影响，不会被误当成多基因。

## 3. 指定 GTF/GFF 作为基因搜索来源

IGV 原生不支持用 GTF 轨道搜索基因。myigv 在搜索框右侧加了**来源下拉框**：可选择内置注释，或任一已加载的
GTF/GFF/GFF3 轨道。选中某个 GTF 后，搜索只在该文件的基因名里匹配；匹配不到会提示
`Cannot find gene or locus in xxx.gtf`，不会悄悄回退到别的源。

## 4. 导入即自动排序 + 建索引

IGV 对乱序的 GTF 直接建索引会失败。myigv 在导入时自动检测：

- GTF/GFF 乱序 → 生成排序副本 `xxx.sorted.gtf` 并建 tribble `.idx`（**不改原文件**）；
- FASTA 缺 `.fai` → 静默建索引；行长不一致时先规整为每行 80 碱基再建。

全程无弹窗。可用 `-Dmyigv.autoIndex=false` 关闭。

## 5. 搜索框回填规范基因名

搜索命中后，搜索框显示**规范基因名**（如 `JUN`），而不是基因组坐标；可继续用逗号追加更多基因。
坐标搜索仍按原样显示坐标。

## 6. 去掉 GTF 的「长实心条」

每行 `gene` 常被画成一条贯穿全程的实心矩形。myigv 过滤掉**无外显子的 gene/pseudogene 容器记录**，
只保留有外显子结构的转录本；纯 gene 文件仍会正常显示。

## 7. 分面级 Group Autoscale

多基因分面时，原生 IGV 的 Group Autoscale 会把所有分面合成一个 Y 范围，高信号基因把低信号组压平。
myigv 默认**每个分面独立 group autoscale**。可在轨道右键 → Group Autoscale 下的
**"Autoscale each gene panel separately"** 切换（关闭即恢复原版行为）。

## 8. Ctrl + 滚轮以鼠标位置缩放

按住 **Ctrl（macOS 为 ⌘）滚动滚轮**，以光标所在位点为锚点缩放，鼠标下的位置保持不动。
滚一格缩放一级；不按 Ctrl 时滚轮仍是纵向滚动轨道。

## 9. Y 轴范围标签可调

轨道右键 → **Y-Axis Range Label**：

- **Font size**：8 / 9 / 10 / 11 / 12 / 14 / 16 / 18 / 20；
- **Position**：**Top left**（默认）/ **Top right**。

即时生效并记住。

## 10. 一键安装包（GitHub Actions）

`.github/workflows/build-installers.yml` 在**打 `v*` 标签**或**手动触发**时（日常 push 只跑编译检查，不打包），
分别在 `windows-latest` / `macos-latest` 上构建 `jpackage` 安装包（自带 Java 运行时 + 离线注释包）：

- Windows：`.exe` 安装器（WiX）；
- macOS：`.dmg`（未签名，首次打开需「右键 → 打开」）。

产物作为 Actions Artifact 上传；打标签时自动附加到 Release。

## 11. 压缩文件直接读取（.gtf.gz / .fa.gz）

`.gtf.gz` / `.gff.gz` / `.fa.gz` / `.fasta.gz` 可以直接导入，不需要先手动解压：

- **注释文件**：`.gtf.gz` / `.gff.gz` 会先自动解压成同目录的 `.gtf` / `.gff`（若已存在且比压缩文件新则复用），
  再走「排序 + 建索引」流程，因此仍然可以随机访问、也能被基因搜索使用；
- **参考基因组 FASTA**：`.fa.gz` / `.fasta.gz` 会自动解压成 `.fa` 并生成 `.fai`，用该 FASTA 建立基因组；
- 已经是 **bgzip + tabix**（注释带 `.tbi`）或 **bgzip + `.gzi` / `.fai`**（FASTA）的文件不会被解压，按索引直接读取。

解压只在第一次导入时发生一次，**原压缩文件不会被修改**；代价是需要一份解压后的副本（约为解压后大小）。
解压过程中状态栏会显示进度提示，可用 `-Dmyigv.autoIndex=false` 关闭整套自动处理。

## 12. 展开视图可切换转录本标签

基因结构 **Expanded** 时，同一基因的每个转录本行默认都显示基因名（如 `HESX1`），无法区分具体转录本。
现在在轨道上右键 → **Transcript label** 可以切换：

| 选项 | 显示 |
| :-- | :-- |
| **Gene name** | 基因名（默认，等同原版 IGV） |
| **Transcript ID (accession)** | RefSeq 显示 `NM_/NR_` 登录号；GTF 显示 `transcript_id`（如 `ENST...`） |
| **Transcript name (readable)** | 优先 `transcript_name`（如 `HESX1-201`），没有则回退到登录号 |
| **Gene + ID** | `HESX1 (NM_003865.3)` |
| **Custom field...** | 任意属性名，如 `gene_id` |

选择只作用于当前轨道，并随 session 一起保存。勾选 **Use for new annotation tracks** 可把它设为
**新打开注释轨道的全局默认**（之后再右键切回 Gene name 可覆盖默认）。

> 提示：长登录号（如 `ENST00000382410.4`）在很窄的转录本上可能放不下——渲染器会自动跳过放不下的标签，
> 属正常现象；放大后再看即可。

---

## 13. Hi-C 矩阵轨道

接触矩阵可以直接拖进窗口，类型由文件内容自动识别，不需要先转格式：

| 格式 | 来源 | 分辨率 |
| :-- | :-- | :-- |
| `.h5` | HiCExplorer / FAN-C（PyTables + blosc 压缩） | 单档（文件自带 bin） |
| `.cool` | cooler 单分辨率 | 单档 |
| `.mcool` | cooler 多分辨率 | **多档**，菜单可选、随缩放自动切 |
| `.hic` | Juicer / Aiden Lab（按块按需读取） | **多档**，同上 |

四种格式都支持 `.gz` 包裹（首次导入自动解压一份副本，原文件不动），且必须是本地文件。

- **显示方式**：`Hi-C display` 里可在**三角形**（矩阵旋转 45°，横轴是两端的中点、纵轴是两点距离）
  与**方形**（双轴都是基因组坐标，和上方 ruler 对齐）之间切换；方形模式提供 `Make track square`，
  让轨道高度等于面板宽度，这样每个 contact 单元才是正方形。
- **配色**：内置 RdYlBu_r 等调色板与自定义三色渐变；数值变换支持 `log1p`（默认，与 pyGenomeTracks 一致）/
  `log` / `-log`；上下限按 pyGenomeTracks 的思路取「近对角线的第 80 百分位」作上限、「远端接触的中位数」作下限，
  也可以手动 `Set maximum value`。
- **图例**：`Hi-C display → Color legend` 可在矩阵内左上/右上画竖直色标，**色标下限固定为 0**，
  随配色与变换实时更新；图例画在缓存图之外，所以翻转热图时位置与文字保持不变。
- **分辨率**：`Hi-C display → Resolution` 默认 `Automatic`，按「每档至少占 2 像素、窗口内不超过 250 个 bin」
  自动挑档；`.hic` 与 `.mcool` 会列出文件里的**全部档位**，手动选定后保持不动。
- **其它**：`Flip vertically` 上下镜像（TAD 叠加一起翻转）、`Set maximum depth` 限制三角形最大显示距离、
  `Set triangle height` 调整顶点高度。

性能上，`.hic` 按块解压、块缓存上限 512 MB；矩阵在后台线程渲染，缩放时先显示上一帧再替换，界面不会卡住。

## 14. TAD 轮廓叠加在矩阵上

TAD / domain 的 BED 可以直接画在 Hi-C 热图上，不用在矩阵和 BED 轨道之间来回对照：

- 右键 Hi-C 轨道 → **TAD domains**：
  - **`Select multiple...`**：弹出复选框列表，一次勾选任意多个 BED 后回车生效，不必每次重开菜单；
  - 每个 BED 一个子菜单，标题前的色块就是它的线色，内含 `Show outline` 开关，以及**只作用于该文件**的
    线宽（1–3 px）、线型（实线/虚线/点线）、线色、填充色、填充透明度；
  - 叠加多个文件时另有 `All outlines ▸` 批量统一、`Clear all` 一键清空。
- 三角形模式下每个 domain 画成「底边落在对角线上、顶点在区间中点上方的距离处」的三角形；
  方形模式下画成沿对角线的方框。
- 描边在 2 倍超采样的透明图层上绘制再缩回，1–2 px 的 45° 细线不会断成一串点。
- 被叠加的 BED 轨道会自动从面板隐藏（避免同一批区间显示两次），**取消勾选或删除 Hi-C 轨道即恢复**；
  多条 Hi-C 共用同一个 BED 时按引用计数处理。
- 支持多对多：一条 Hi-C 可挂任意多个 BED，同一个 BED 也能被多条 Hi-C 引用；绑定关系与样式随 session 保存。

## 15. BEDPE 弧线（loops / arcs）

`.bedpe` 文件会加载成 interaction 轨道，用于画染色质环、ChIP-loop 一类的两两互作：

- **两种高度模式**：`Nested Arcs`（弧高随跨度，嵌套排布）与 `Proportional Arcs`
  （弧高由选定列的数值决定，`Value Column` 会列出文件里所有能解析成数字的列）。
- **三种弧型**：`Arc shape` 可选 `Circular arc`（圆弧，原版风格）/ `Ellipse arc`（半椭圆，两端垂直落地、
  顶部圆润，接近论文插图）/ `Bezier arc`（三次贝塞尔，顶部更平，弧线密集时层次更清楚）。
  三种形状的弧顶高度一致，切换只改曲率。
- **配色**：`Set Arc Color...` 统一设色，或按文件里的 `color` 列逐条上色；`Reset Arc Colors To File` 恢复。
- **方向**：`Arc direction` 可让弧线朝上或朝下。
- **多文件**：一个 BEDPE 对应一条轨道，多条轨道各自保存样式（弧高模式、弧型、颜色互不影响）。
- 解析器兼容 **UTF-16LE/BE 带 BOM** 的文件（Windows 下 `Out-File` 的默认编码），
  也接受只有前 6 列（chrom1/start1/end1/chrom2/start2/end2）的极简写法。

## 16. SSH / SFTP 远程数据

不用先把服务器上的文件下载到本地，myigv 可以直连 SSH 服务器、浏览远程目录并**直接读取**其中的轨道：

- **入口**：菜单 **File → Load from SSH Server...**（快捷键 `H`）。
- **连接**：`Saved` 里可选已保存的服务器；填 `Host` / `Port` / `User` / `Password` 后点 **Test connection** 验证，
  连接成功后打开远程文件浏览器。
- **浏览与加载**：文件浏览器用 **Up** 返回上级、双击目录进入；选中一个或多个文件点 **Open** 即加载为轨道。
  读取走 SFTP 随机访问，支持需要 seek 的格式（BAM/CRAM、bigWig、Hi-C `.cool`/`.mcool`/`.hic` 等）。
- **凭据**：连接信息保存在 myigv 数据目录的 `ssh-servers.json`，密码用**系统级加密**
  （Windows DPAPI / macOS 钥匙串），不落明文。
- **代理兼容**：SSH 默认**显式直连**，即使开启系统代理/VPN 也不会被 HTTP 代理劫持握手
  （修复了 `-Djava.net.useSystemProxies=true` 下 `connection is closed by foreign host` 的问题）；
  确需走代理的场景可加 `-Dmyigv.ssh.useSystemProxy=true`。

## 17. Feature 轨道「显示箭头」

BED / GFF 等 Feature 轨道可在右键菜单里按正负链显示方向箭头：

- 右键轨道 → **Show Arrows**（**默认关闭**），勾选后按每个 feature 的 `strand` 画方向箭头。
- **窄位点**（当前缩放下不足约 6 像素，例如 CTCF 位点）画**定长方向小箭头**：正链 `>`、负链 `<`，
  任何缩放级别都看得见；**较宽的位点**则在色块内画重复的白色小箭头。
- **链信息校验**：若文件的链信息列不是合法的 `+` / `-`（解析后全部为「无链」），勾选 `Show Arrows` 时会弹框
  提示「链信息不符」，避免误以为渲染出错。
- 取消勾选即恢复普通色块显示。

---

## 快速开始

### 使用安装包

到 **Actions → Build installers** 下载对应 Artifact（或从 Release 下载）：

- Windows：运行 `myigv-1.0.2.exe` 安装；
- macOS：打开 `myigv-1.0.2.dmg`，把 myigv 拖入 Applications。

安装包已自带运行时与离线注释，装完即用，无需另装 Java。

> **macOS 首次打开提示「Apple 无法验证 myigv 是否包含恶意软件」？**
> 这是 Gatekeeper 对所有**未经 Apple 公证**的第三方应用的统一拦截，不是病毒，也不是安装包损坏。
> 处理办法见下一节。

#### macOS 首次打开被拦截怎么办

**macOS 15 (Sequoia) 及以上**（弹窗只有 `Move to Trash` / `Done` 两个按钮）：

1. 点 **`Done`**（不要点 `Move to Trash`）；
2. 打开 **系统设置 → 隐私与安全性**，往下滚到「安全性」区域，会看到
   *「"myigv" 已被阻止，因为无法验证开发者」*；
3. 点 **`仍要打开`**（Open Anyway），输入开机密码；
4. 再启动一次 myigv，在弹窗里点 **`打开`**。

**macOS 14 及更早**：在 Finder 里**右键（或按住 Control 点击）** myigv → `打开` → 在弹窗里再点 `打开`。

**命令行方式（最省事，一次搞定）**：

```bash
xattr -dr com.apple.quarantine /Applications/myigv.app
open /Applications/myigv.app
```

如果装在其他位置，把路径换成实际的 `myigv.app` 路径。这条命令只是去掉「从网络下载」的隔离标记，
不会修改应用本身的任何文件。

> 根治办法是由项目用 Apple Developer ID 签名并做公证（notarization），需要付费的 Apple Developer 账号；
> 在此之前，上面三种方式都需要用户手动放行一次，之后不再提示。

### 从源码构建

需要 **JDK 21**：

```bash
./gradlew createDist          # Windows: gradlew.bat createDist
```

产物在 `build/myigv-dist/`，启动：

```bash
build/myigv-dist/myigv.bat    # Windows
build/myigv-dist/myigv.sh     # Linux / macOS
```

## 与上游 IGV 的关系

- 本项目基于 **IGV 2.19.8**，遵循 MIT 许可（见 `license.txt`）。
- 上游原始 README 保留在 [`README-IGV.md`](README-IGV.md)。
- 更详细的逐项实现说明与改动记录见 [`README-myigv.md`](README-myigv.md)。

## License

MIT
