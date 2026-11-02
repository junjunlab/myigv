# myigv —— 自带离线注释包的 IGV

基于 **IGV 2.19.8** 源码构建，把 IGV 打开时原本需要联网下载的**注释类资源**全部预打包进安装包，
使软件在断网/弱网环境下也能正常加载基因组与注释；而**参考基因组序列**（`.fa` / `.2bit`）仍保持
IGV 原逻辑——联网按需获取，放大到一定分辨率后才联网下载展示。

## 如何启动

直接双击根目录下的 **`启动myigv.bat`** 即可（首次会自动用 `gradlew createDist` 构建发行包）。

- `启动myigv.bat`：静默 GUI 启动（无控制台窗口），推荐日常使用。
- `启动myigv-控制台.bat`：带控制台窗口启动，**排错时请用它**——任何启动报错都会打印在窗口里。

要求：根目录需带有 `jdk-21`（已随工程附带）；否则会回退到系统 `java`。

## 离线能力说明

**已打包（断网可用，共 119 个资源 ~86MB）：**

- 18 个 Hosted Genomes 对话框中的基因组配置（`.json` / `.gbk`）；
- 染色体大小、染色体带（cytoBand）、别名、chromAlias 等辅助文件；
- 默认自动加载的注释轨道：如 hg38 的 **Refseq All**（ncbiRefSeq）、MANE（mane.1.0.bb/.ix）、
  rsdb（refSeqHistorical.bb）等；
- 基因组下拉列表与 hub 索引（`genomes2.tsv`、`genomes.txt`、`url_mappings.tsv` 及各 hub 的
  `hub.txt` / `genomes.txt` / `trackDb.txt`），让“Tracks”菜单离线可列目录。

**仍走联网（按原始需求保留）：**

- 参考基因组序列本身：`.fa` / `.fasta` / `.2bit`。当你放大到能显示碱基/序列的层级时，
  IGV 才会联网获取。**这是有意保留的行为**，并非遗漏。

## 数据目录隔离

myigv 通过 `-Dmyigv.dir` 把自身数据目录指向发行包内的 `build/myigv-dist/myigv_data`，
**不再与系统里已安装的 IGV 共用 `~/.igv`**。好处：

- 不会读到 IGV 残留的缓存（例如旧的 GitHub 基因组列表），保证离线行为稳定；
- 不会污染你本机 IGV 的偏好设置与日志。

## 多基因搜索（逗号分隔 → 分面）

搜索框现在支持**逗号或分号分隔**输入多个基因/位点，例如：

```
SOX1,SOX2,SOX3
BRCA1;TP53;EGFR
EGFR, chr1:1,000,000-2,000,000
```

回车后，IGV 会把它们作为一个 **Gene List（Regions）** 处理：视图底部并列铺开每个基因一个
region 面板（这就是“分面”效果，与 IGV 菜单 `Regions → Gene Lists` 的机制完全一致）。
Gene List 里记录的是基因名（feature 命中）或坐标（locus 命中），可在 Regions 面板中查看/导出。

注意：坐标中的逗号是千分位分隔符（如 `chr1:1,000,000-2,000,000`），代码已做保护——
只要搜索串包含冒号就**不会**按逗号拆分，避免破坏坐标。

实现改动：`SearchCommand.runSearch` 增加逗号/分号分隔；`showSearchResult` 的多结果分支
让 feature 命中以基因名存入 GeneList；`SearchTextField` 的实时建议（hint）也按最后一个片段提示。

## 指定 GTF 作为基因搜索来源

搜索框右侧新增 **基因来源下拉框**（默认显示 `Default genome annotation`）：

- 导入 GTF / GFF / GFF3 / GVF（含 `.gz`）文件后，该轨道会自动出现在下拉框中（显示轨道名）；
- 选中某个 GTF 后，搜索框的**实时候选**和**回车搜索**都只用这个文件里的基因名做匹配；
- 匹配支持**基因名**与**基因 ID**：GTF 的 `gene_name` / `gene_id`，GFF3 的 `Name` / `ID`（如
  `gene:ENSG00000141510`）都可直接搜到，大小写不敏感；
- 输入部分基因名（如 `SOX`）回车，会把该 GTF 中所有前缀匹配的基因作为 **Gene List 分面**铺开
  （上限 50 个，避免一次开出上千个面板）；
- 坐标串（`chr1:1,000,000-2,000,000`）不受影响，仍然按位点跳转；
- 切回 `Default genome annotation` 即恢复 IGV 原生行为（基因组自带注释 + UCSC 查询）。

首次选中某个 GTF 时会在后台建立基因索引（整份 GTF 只扫一遍，只读 `gene` 行；若文件里没有
`gene` 行则回退到 transcript/exon 合并区间），鼠标悬停在下拉框上可看到索引进度与基因数量。

实现改动：
- `org/broad/igv/feature/GeneSearchIndex.java`（新增）：GTF/GFF 解析 + 基因名索引（精确/前缀查询）。
- `org/broad/igv/feature/GeneSearchSources.java`（新增）：搜索源列表（默认 + 已加载 GTF 轨道）、
  索引缓存、当前选中源；切换基因组时清空（索引坐标是按旧基因组染色体名规范化的）。
- `IGVCommandBar`：搜索框旁新增下拉框，点击时刷新来源列表。
- `SearchCommand` / `SearchTextField`：选中 GTF 源时改从该索引解析基因名。

## 导入 GTF / FASTA 自动排序与建索引

不需要再手动跑 igvtools，也不会弹「是否创建索引」「文件必须先排序」的对话框。

**GTF / GFF / GFF3 / GVF（本地、未压缩）**

加载时自动完成两步：

1. 检查是否按「染色体 + 起点」有序。若乱序，就在旁边生成排序副本
   `xxx.sorted.gtf` 并加载该副本——**原文件不会被修改**；
2. 若没有 `.idx` 索引，自动建立 tribble 索引。

过程中状态栏会显示 `Sorting ...` / `Indexing ...`，轨道名仍显示你原来的文件名。
排序副本和索引生成一次后会复用，下次导入同一文件不再重复处理。

**FASTA（作为基因组加载）**

`.fai` 索引本来就是自动创建的；本次新增兜底：如果 FASTA 的**行长不一致**（这类文件无法直接建索引，
以前会报错让你自己处理），会自动写一份规范化副本 `xxx.regularized.fa`（每行 80 碱基）并为它建索引。

**说明与开关**

- 压缩的 GTF（`.gtf.gz` / `.bgz`）不做自动处理——那需要 bgzip + tabix 重压缩，仍按非索引方式加载。
- 目录只读等导致写入失败时只会记录日志，文件照常加载，不会中断。
- 如需关闭自动处理：启动参数加 `-Dmyigv.autoIndex=false`，或设置环境变量 `MYIGV_AUTO_INDEX=false`。

实现改动：
- `org/broad/igv/util/AutoIndexer.java`（新增）：乱序检测、调用 `SorterFactory` 排序、建 tribble 索引。
- `TrackLoader.load`：加载前调用 `AutoIndexer.ensureSortedAndIndexed`，必要时把 locator 指向排序副本
  （同时保留原文件名作为轨道名）。
- `FastaUtils.ensureIndexed` + `FastaGenomeLoader`：静默建 `.fai`，行长不一致时自动规范化并重试。

## 搜索框显示基因名（不显示坐标）

原生 IGV 的搜索框同时被当作「当前位置显示」——一跳转就把它替换成 `chr17:7,659,779-7,689,546`
这样的坐标，导致刚搜的基因名被覆盖，也无法继续往里追加基因。现在改为：

- 搜到基因后，搜索框回填**规范基因名**（你输入 `sox1` / `egfr` 会变成 `SOX1` / `EGFR`）；
- 只搜到基因时**不再回填坐标**；跳转、缩放、平移都不会把基因名冲掉；
- 光标停在文本末尾，可直接输入 `,SOX2` 继续追加（配合逗号分隔的多基因分面）；
- 输入坐标（`chr1:100,000-200,000`）或以染色体名搜索时，文本保持你输入的内容不变；
- 自己改过文本、切换基因组、或切到 gene list 模式后，坐标回填恢复正常。

实现改动：`SearchCommand` 增加 `resolvedGeneTextFor()`（把命中结果转成基因名列表，坐标查询返回
null 即不改写）与回调；`SearchTextField` 用 `showingResolvedGenes` 状态标记「当前显示的是基因名」；
`IGVCommandBar.updateCurrentCoordinates()` 在该状态下跳过坐标回填。

## GTF 轨道不再画「长实心条」

Ensembl / GENCODE 的 GTF 里，每个基因除了 transcript / exon / CDS 之外，还有**一条 `gene` 记录**。
这条记录只有起止、没有外显子结构，IGV 会把它画成一根横跨整个基因的实心长条，正好盖在下面
真正的转录本模型上——看起来就是"一条很长的柱子"。

现在渲染时会跳过这类**没有结构的 gene/pseudogene 容器记录**，只保留转录本模型
（外显子方块 + 内含子连线 + 链方向箭头）：

- `xxx_gene` 之类、且自身带外显子的记录**不会**被误删（GFF3 里基因可能直接持有外显子）；
- 如果一个文件里**只有** gene 行、没有 transcript/exon（纯基因区间注释），则照常显示，不会被清空；
- 基因名搜索不受影响（索引来自原始文件，`FeatureDB` 也照常收）。

实现改动：`GFFFeatureSource.getFeatures` 在合并后过滤掉 `hasExons()==false` 的 gene 容器。

> 附注：Ensembl GTF 的行顺序是「按基因起点排序」，同一个基因内部各转录本的起始位置会来回跳，
> 因此 htsjdk 无法直接建索引。这正是上一节自动排序会生成 `*.sorted.gtf` 副本的原因（原文件不动），
> 生成一次后复用，不会重复排序。

## 基因分面下的 Group Autoscale 可以按分面各自缩放

多基因分面（gene list）时，几个 bigwig 做 Group Autoscale，原生 IGV 会把**所有分面**的数据合起来
算一个范围，于是高信号基因把整体 Y 轴拉得很高，低信号基因（比如 input 组）的信号被压平，看不出差别。

现在 Group Autoscale 默认**每个分面各算各的**：同一分面内的所有 Group 成员共用一个范围，不同分面
互不影响。上例中 JUN 面板可能是 `[0 - 30]`，JUND 面板 `[0 - 169]`，input 组的峰也能看清。

**怎么切换**：在轨道上右键 → 勾选/取消 **"Autoscale each gene panel separately"**（就在
"Group Autoscale" 下面）。取消后恢复原 IGV 行为（全视图共享一个范围）。选择会记住。

实现改动：
- `Track` 新增 `getDataRange(ReferenceFrame)`（默认返回全局范围）；
- `AbstractTrack` 保存「面板名 → DataRange」映射，`setDataRange(DataRange)` 会自动清掉分面值；
- `Autoscaler` 在 gene list 模式且选项开启时，按每个 `ReferenceFrame` 分别计算；
- `XYPlotRenderer` / `LineplotRenderer` 渲染时按当前分面取范围（含左侧 `[min - max]` 轴标签）。

## Ctrl + 滚轮缩放视图（修复 + 缩放到光标）

除了原有的坐标栏 `+/-`、缩放滑块、右键菜单缩放之外，myigv 支持 **按住 Ctrl（Mac 上为 ⌘ / Meta）滚轮** 缩放当前视图。

IGV 2.19.8 其实早已写了这个逻辑，但有两个毛病，导致它基本「没反应」：

1. 原来写的是 `zoomIncr = -wheelRotation / 2`。Java 整数除法下，滚一格（`wheelRotation = ±1`）
   会得到 `0`，等于不缩放；要连滚两格才动一下，手感像坏了。
2. 缩放围绕**视图中心**进行，而不是鼠标所在位置，光标下的位点会乱跑。

现在改成：

- 滚一格即缩放一级（向上滚 = 放大，向下滚 = 缩小），响应灵敏；
- **缩放到光标所在位置**（zoom-to-cursor）：以鼠标当前基因组坐标为中心缩放，光标下的位点保持不动，
  体验接近地图/浏览器。

实现改动（仅 `src/main/java/org/broad/igv/ui/panel/DataPanel.java` 的 `mouseWheelMoved`）：

- 计算 `zoomIncr = -wheelRotation`（去掉 `/2`）；
- **真正以鼠标位置为锚点缩放**：`ReferenceFrame` 新增
  `doIncrementZoomAtCursor(zoomIncr, cursorGenomePos, cursorPixelX)`。它先 `setZoom` 改变级数并
  重算 scale，再按公式 `origin' = 光标基因组坐标 − 新scale × 光标像素X` 重设 origin，使**鼠标下方的
  那个基因组位点缩放后仍停在鼠标那个 X 像素处**（而不是被挪到屏幕中央）。最后 `e.consume()` 避免
  同时触发纵向滚动。

> 注意：Ctrl+ 滚轮只缩放**鼠标所在的那个分面**（单视图或多基因分面中的某一行）；
> 没有 Ctrl/Meta 时，滚轮仍按原行为纵向滚动轨道列表。

## Y 轴范围标签：可调字号与位置（右键菜单）

bigwig / 折线等数据轨道左上角会显示一个 `[min - max]` 的 Y 轴范围标签。现在可以在轨道上
**右键 → Y-Axis Range Label** 里调整：

- **Font size**（字号）：8、9、10、11、12、14、16、18、20，单选项，默认 8；
- **Position**（位置）：**Top left**（左上，默认）/ **Top right**（右上）。

选择即时生效（不用重启），并会记住到偏好设置里。该设置为**全局**，影响所有数据轨道的范围标签。

实现改动：

- `Constants` 新增 `AXIS_RANGE_FONT_SIZE`、`AXIS_RANGE_POSITION`；
  `preferences.tab` 两处（描述区 + 默认值区），默认 `8` / `left`。
- `DataRenderer.drawScale`：按偏好设置 `FontManager.getFont(size)`，用 `FontMetrics` 计算基线
  （字号变大也不会被顶部裁掉）；位置为 `right` 时用 `arect.x + arect.width - 文本宽度 - 5` 右对齐，
  否则 `arect.x + 5` 左对齐。
- `TrackMenuUtils.getAxisRangeLabelItem()`：在数据轨道右键菜单（"Show Data Range" 下方）新增
  "Y-Axis Range Label" 子菜单，含字号与位置两组单选按钮；切换后 `IGV.repaint()`。

> 补充：若已勾选 `CHART_DRAW_Y_AXIS`（左侧带刻度的完整 Y 轴），则走的是另一条绘制路径，
> 本标签不显示；上面的设置只作用于默认的 `[min - max]` 角标。

## 实现要点（供二次开发参考）

- `src/main/java/org/broad/igv/local/LocalResourcePack.java`：资源包核心类，读取
  `pack/manifest.tsv`（`原始URL \t 本地相对路径`），在 I/O 边界做 URL→本地文件映射，不改动
  `ResourceLocator` 的原始 URL。
- I/O 挂钩点：`ParsingUtils`、`IGVSeekableStreamFactory`、`HttpUtils`（读取/连接/大小/可用性）。
- `src/main/java/org/broad/igv/DirectoryManager.java`：新增 `-Dmyigv.dir` 覆盖，隔离用户数据目录。
- `src/main/java/org/broad/igv/ui/commandbar/GenomeListManager.java`：默认基因组指向
  `https://igv.org/genomes/json/hg38.json`（已在包内），避免离线时卡在 GitHub。
- 应用名已改为 `myigv`（窗口标题、`Application-Name`）。

## 重新生成资源包

注释资源由 `tools/myigv_pack.py` 生成：

```bash
python tools/myigv_pack.py            # 增量打包（已有文件跳过）
python tools/myigv_pack.py --clean    # 清空 pack/ 后重新下载
```

改完 `pack/` 后需重新构建发行包：`gradlew createDist`（会把 `pack/` 复制进 `build/myigv-dist/pack`）。

## 自动打包安装包（Windows .exe / macOS .dmg）

仓库自带 GitHub Actions 工作流 `.github/workflows/build-installers.yml`，在 push 到 `main`、打 `v*`
标签或手动触发（workflow_dispatch）时，分别在 `windows-latest` 与 `macos-latest` 上执行：

1. `./gradlew createDist` 构建 `build/myigv-dist`（含 `lib/` 与 `pack/`）；
2. `jlink --add-modules ALL-MODULE-PATH` 生成运行时镜像。这里**只链接 JDK 自身模块**，因为把 IGV 的
   自动模块一起交给 jlink 会失败（例如 `batik.xml`）；再用 `jpackage --runtime-image` 直接复用它；
3. `jpackage` 生成安装包，并通过 `--app-content` 把 `pack/` 一并打入，保证开箱即离线：
   - **Windows**：`.exe` 安装器（需要 WiX，工作流用 `choco install wixtoolset` 安装）；
   - **macOS**：`.dmg`。

产物作为 workflow artifact 上传；打 `v*` 标签时还会自动附加到对应的 GitHub Release。

> macOS 安装包未做代码签名/公证，首次打开需「右键 → 打开」以绕过 Gatekeeper。

配套改动：`LocalResourcePack` 现在会在 `app-content/pack`、`app/pack`、`<root>/pack` 等位置查找离线包，
因此 jpackage 生成的目录布局也能自动识别（无需再传 `-Dmyigv.pack`）。
