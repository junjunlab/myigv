# myigv —— 自带离线注释包的 IGV

基于 **IGV 2.19.8** 源码构建，把 IGV 打开时原本需要联网下载的**注释类资源**全部预打包进安装包，
使软件在断网/弱网环境下也能正常加载基因组与注释；而**参考基因组序列**（`.fa` / `.2bit`）仍保持
IGV 原逻辑——联网按需获取，放大到一定分辨率后才联网下载展示。

## 如何启动

推荐直接使用安装包（Windows `.exe` / macOS `.dmg`，自带 Java 运行时与离线注释）。

从源码运行（需要 JDK 21）：

```bash
./gradlew createDist            # Windows: gradlew.bat createDist
build/myigv-dist/myigv.bat      # Windows
build/myigv-dist/myigv.sh       # Linux / macOS
```

排错时从终端运行上面的启动脚本，错误会直接打印到终端窗口。

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

- **压缩文件直接读取**：`.gtf.gz` / `.gff.gz` 会先解压成同目录的 `.gtf` / `.gff`（已有且较新则复用），
  再排序 + 建索引；`.fa.gz` / `.fasta.gz` 同理先解压成 `.fa` 再建 `.fai`。已经是 bgzip + tabix（`.tbi`）
  或 bgzip + `.gzi`/`.fai` 的文件不解压，按索引直接读取。原压缩文件不会被修改。
- 目录只读等导致写入失败时只会记录日志，文件照常加载，不会中断。
- 如需关闭自动处理：启动参数加 `-Dmyigv.autoIndex=false`，或设置环境变量 `MYIGV_AUTO_INDEX=false`。

实现改动：
- `org/broad/igv/util/AutoIndexer.java`（新增）：乱序检测、调用 `SorterFactory` 排序、建 tribble 索引。
- `TrackLoader.load`：加载前调用 `AutoIndexer.ensureSortedAndIndexed`，必要时把 locator 指向排序副本
  （同时保留原文件名作为轨道名）。
- `FastaUtils.ensureIndexed` + `FastaGenomeLoader`：静默建 `.fai`，行长不一致时自动规范化并重试。
- `org/broad/igv/util/GzipUtils.java`（新增）：识别 gzip / BGZF，按需解压到同级文件（复用较新副本、原子替换）。
- `GenomeLoader.getLoader`：`.fa.gz` 不再直接报错，先解压再走 FASTA 索引流程。

## 加载 BAM 时自动建索引

原生 IGV 要求 BAM 旁边先有 `.bai`，否则弹窗让你手动输入索引路径。myigv 改为**就地建索引**：

- 本地 `.bam` 找不到 `.bai` / `.csi` 时，自动在同目录生成 `<文件名>.bam.bai`，建完直接加载，不再打断操作；
- 索引先写到临时文件，**成功后才原子替换**，中途取消或失败不会留下半截 `.bai`；
- 状态栏显示 `No index found - creating xxx.bam.bai ...`，完成后显示 `BAM index created: <路径>`；
- 同一文件被多个轨道并发加载时加锁串行，不会重复建两遍。

**失败时**（BAM 未按坐标排序、目录不可写等），弹窗会说明具体原因，并保留手动输入索引路径的入口，
不再是原来那句 `Index is required, but no index found.`：

- 未排序：提示 `BAM file is not coordinate sorted (header sort order = ...)`，需先 `samtools sort`；
- 远程 BAM（http/https）：无法在本地建索引，弹窗会明确说明，并请你填写索引 URL。

实测：272 MB 的 STAR 输出 BAM 建索引约 12 秒，生成索引 263 KB，坐标查询正常。

实现改动：
- `org/broad/igv/sam/reader/BamIndexBuilder.java`（新增）：用 htsjdk `BAMIndexer` 建索引，写临时文件后原子替换，并校验排序方式。
- `TribbleFeatureSource.toReaderPath`：本地 BED 路径在交给 htsjdk 前统一转成 `file:` URI，
  修掉命令行/端口加载绝对 Windows 路径时报 `No FileSystemProvider` / `Illegal char <:>` 的问题；
- `SamReaderPool.getIndexPath`：本地 BAM 无索引时先尝试自动创建；失败才进入「输入索引路径」对话框，且文案改为说明失败原因。

## Hi-C 接触矩阵可视化

参照 [pyGenomeTracks](https://github.com/deeptools/pyGenomeTracks) 的 `hic_matrix` / `hic_matrix_square` 实现，
把 Hi-C 热图移植进 myigv，可直接加载接触矩阵：

- **支持格式**：
  - HiCExplorer `.h5`（PyTables + blosc 压缩）
  - cooler `.cool` / `.mcool`：`.cool` 单分辨率；`.mcool` 里的每一档分辨率都能用（`Hi-C display → Resolution`
    列出全部档位，默认从最接近 10 kb 的那档开始，之后随视口自动切档，切过的档会缓存在内存里）
  - Juicer `.hic`（Aiden Lab 格式，**按需读取 block**，不会把几百 MB 的文件整体载入内存；
    文件里若有多档分辨率，按视口自动切换）
  - gzip 包装的 `.cool.gz` / `.h5.gz`：首次加载自动解压到同目录，之后直接复用；
- **两种显示**：三角形（矩阵旋转 45°，x 为两个锚点的中点、y 为两点距离）与方形（双轴均为基因组坐标，对角线左上→右下）；
- **配色**：`RdYlBu_r`（深蓝 → 黄 → 深红），上限取紧邻对角线值的 80 分位、下限取远距离接触的中位数，与 pyGenomeTracks 的取值方式一致；
- **BEDPE interaction 轨道（loops / arcs）**：
  - 默认 `Nested Arcs`；不再读取全局偏好或按 10x 文件类型推断，避免上一次的选择污染新轨道；
  - 删除无效的 `Set Track Color...`（文件带 `color` 列时逐条颜色永远优先），
    改为 `Set Arc Color...`（统一设置所有曲线颜色，优先级高于逐条色）+ `Reset Arc Colors To File`（恢复文件配色）；
  - `Value Column (Proportional Arcs)`：选择用哪一列驱动弧高，菜单自动列出能解析为数值的列，
    默认 `score`；`Autoscale` / `Set Max Score` 会按所选列重新归一化，列选择随 session 保存。
  - `Arc shape`：曲线形状，三种，按轨道保存（session 属性 `arcStyle`，默认 `Circular arc`）：
    - `Circular arc`（默认）——圆弧（旧版 IGV 的样子），两端与基线成一定角度；按强度定高时若高度超过半圆，
      自动截断为半圆，否则圆弧会绕成接近整圆、看起来自我交叉；
    - `Ellipse arc`——半椭圆，两端垂直落在基线上、顶部圆润，是 HiCExplorer / pyGenomeTracks
      论文插图常见的画法；
    - `Bezier arc`——三次贝塞尔拱（控制点在 4/3 高度），顶部更平，适合大量弧线重叠时看清层次。
    嵌套模式下三种形状的弧顶高度一致（都取自同一个 sagitta），只是曲率不同；
    `Proportional Arcs` 模式下高度由所选数值列决定，配合 `Ellipse arc` 最接近发表的 loop track 效果。
- **交互**：
  - `Hi-C display → Color legend`：在热图内画竖直色标图例，`Top left` / `Top right` / `None`（默认左上）。
    色条与当前配色、数值变换（log1p / log / -log）完全一致，标注变换后的 vmin/vmax；
    **色标下限固定为 0**（`log1p`/`线性` 下就是 0，`log`/`-log` 下是 log10(floor)，与 HiCExplorer 默认一致），
    不再用远端接触的中位数当下限——旧行为会把整个低值区压成同一个深色，图例也会从一个莫名其妙的数值开始。
    色条按**整幅图像**绘制（`BufferedImage` + 双线性缩放）而不是逐行 `drawLine`：
    150% 缩放下逻辑 1 px 的横线落在设备像素行之间，会和白底混色，色条里出现一道道**横向白缝**。
    图例画在缓存图之外，所以**翻转热图时位置与文字保持不变**，改位置也不会重算矩阵。状态随 session 保存。
    数值标注在色条**右侧**：顶端对齐 vmax、底端对齐 vmin（下限 0），框的尺寸与位置按「轨道 rect ∩ 实际绘制裁剪区」
    计算——轨道被滚出视野或窗口过矮时，裁剪区比 rect 小，若按 rect 定位会把框底连同 vmin 一起裁掉（曾出现「图例没有 0」）。
    可见高度不足 78 px 时整体不画，而不是画一个被切掉一半的框。
  - `Hi-C display`：切换三角形 / 方形；
    - `Flip vertically (inverted triangle)`：整幅轨道上下镜像，对角线移到上沿、三角形顶点朝下
      （等价于 pyGenomeTracks 的 `orientation: inverted`）。翻转作用在**合成完成的图像**上，
      因此矩阵和所有 TAD overlay 一起翻转——之后才勾选的 BED 也自动对齐，overlay 不需要记录朝向；
      方形模式下同样做上下镜像。状态随 session 的 `flipped` 属性保存，切换只重新合成、不重算矩阵。
    - `Set maximum depth...`：三角形模式最大显示距离（bp），留空 = 自动；
    - `Set triangle height (0-1)...`：对角线到顶点之间的距离，按视窗跨度的比例给，
      `1` = 整个三角形一直到顶点正好顶到轨道上沿，`0.5` = 只画近对角线的一半（截断的梯形），
      留空 = 自动（保持 45° 等比，单元为正方形）；设置该项时接触对会裁剪到当前视窗内，才会出现真正的顶点；
    - `Make track square (height = width)`：方形模式下列表和轨道的两轴都是同一个基因组区间，
      只有画布是正方形时单元才是正方形；点这一项把轨道高度设成面板宽度，矩阵即为满幅正方形。
  - `TAD domains`：把已加载的 BED/特征轨道叠加到热图上，可多选（每个 BED 自动分配一种线色）。
    三角形模式下每个 domain 画成"底边在对角线、顶点在区间中点上方"的闭合三角形，方形模式画沿对角线的方框。
    参数：线宽（1/1.5/2/2.5/3 px，默认 2 px）、线型（实线/虚线/点线）、线色、填充色、填充透明度（默认 0%，完全透明）。
    菜单结构：`Select multiple...`（弹复选框对话框，勾任意多个文件后回车一次性生效）、
    每个 BED 一个子菜单（标题前的色块 = 该 overlay 当前线色），内含 `Show outline` 开关 +
    该文件**独有**的线宽/线型/线色/填充色/填充透明度；叠加两个以上时还有 `All outlines ▸` 批量修改全部。
    未启用的文件只显示 `Show outline`，避免出现改了也不生效的项。线色/填充色提供 6 个预设色 + `More colors...`。
    描边画在 2 倍超采样的透明图层上再双线性缩小，并启用 `VALUE_STROKE_PURE`；1–2 px 的 45° 细线直接画在彩色热图上时
    每行只有 1–3 个深色像素，抗锯齿边缘与亮色矩阵混合后肉眼容易看成「一串点」，超采样后是连续实线。
    缓存图按 `Graphics2D` 变换里的 HiDPI 倍率（Windows 150% = 1.5）以**设备分辨率**渲染再 1:1 贴回，
    否则整张逻辑像素图会被系统放大 1.5 倍重采样，把 45° 线拉成一串 1/2 像素交替的方块珠子。
    支持多对多：每条 Hi-C 可挂任意多个 BED，同一个 BED 也可被任意多条 Hi-C 引用；
    同一条 Hi-C 上同一个 BED 按文件路径去重，样式参数按 overlay 独立保存（`lineColor` / `lineWidth` / `lineStyle` / `fillColor` / `fillAlpha`）。
    绑定后 BED 轨道会自动从面板隐藏（避免同一批区间显示两次），取消绑定或改回未勾选即恢复；多条 Hi-C 共用同一 BED 时按引用计数处理。
    绑定关系随 session 保存；菜单标题会显示当前叠加数量，并提供 `Clear all`。
    查询域区间时若当前染色体名查不到，会自动尝试 `X` ↔ `chrX` 的写法，避免 BED 与基因组命名不同导致空图；
    只改线条样式时不会重绘矩阵（复用已算好的矩阵图，只重画描边），响应时间从数百毫秒降到几毫秒；
  - `Hi-C display → Resolution`：多分辨率文件（`.hic` 与 `.mcool`）按视口自动切档，默认 `Automatic`；
    也可固定成 1 kb / 5 kb / … / 2.5 Mb。自动规则有两条约束——每档至少占 2 像素（否则会跳 bin、出现摩尔纹），
    且窗口内不超过 250 个 bin（1 kb 档单块数据量极大，实测 200 kb 窗口要 4.1 s，所以自动模式最低到 5 kb，
    更细的档位留作手动选择）；
  - `Hi-C color → Transform`：取色前的数值变换，四档与 pyGenomeTracks 一致
    （`Linear` / `log1p` / `log` / `-log`，**默认 log1p**）。变换在计算色标之前进行，
    `log` 家族用样本里最小正计数兜底 0 值，`-log` 等于 `log` 加反转色带；
  - `Hi-C color`：切换配色（RdYlBu_r / Viridis / Reds / Blues / Greys / Custom...）、手动设定色标上下限、恢复自动；
    `Custom...` 可选低/中/高三色（中色可跳过），生成平滑渐变，并随 session 保存；
  - 右键菜单里的 `Change Track Color...` 对热图无效，已从 Hi-C 轨道中移除，避免误导。
- **方形模式**：矩阵始终铺满窗口宽度，与上方染色体标尺对齐；切到方形时轨道高度自动设为面板宽度，
  这样单元才是正方形（等价于 pyGenomeTracks 按矩阵比例推导轨道高度的做法）。窗口宽度变化后可再点
  `Make track square` 重新对齐。
- **几何**：三角形模式严格保持 45° 旋转的等比映射（纵向 bp/像素 = 横向的 2 倍，因为旋转后分离度占两倍跨度），
  每个接触单元都是等宽高的菱形（即旋转 45° 的正方形）；`depth` 留空时自动铺满轨道高度，任意缩放比例下形状都不变形。

**无需 Python、无需 native 库**：HDF5 用纯 Java 的 `io.jhdf` 读取；HiCExplorer 的 chunk 用 blosc 压缩
（PyTables 的 `complib='blosc'`），jhdf 原生不支持，因此内置了移植自 c-blosc 1.21.6 的解码器
（`BloscFilter`，HDF5 filter id 32001），支持 blosclz + byte-shuffle。

实测：`Li_et_al_2015.h5`（14 MB，11104 个 bin、166 万非零单元）读取约 0.26 秒，单次绘制约 30 毫秒；
与原文件逐值比对一致（如 bin 8 的 `1054.47482997`）。

实现改动：
- `HiCSource` 接口：把"矩阵"抽象成按 bin 取值的数据源，渲染器不关心文件格式；
- `HicFileSource.java`（新增）：Juicer `.hic` 解析——header / master index / 按染色体对的 block 表 / zlib block；
  block 解压后按需缓存（LRU 8 个），实测单次绘制 26–146 ms；
- `org/broad/igv/hic/BloscDecompressor.java`、`BlosclzDecompressor.java`、`BloscFilter.java`：纯 Java blosc 解码，注册到 jhdf；
- `HiCFileReader.java`：识别并读取 HiCExplorer / cooler 两种布局（CSR 与 COO→CSR）；
- `HiCData.java`：内存矩阵模型（按染色体分 bin、稀疏取值、区域子矩阵）；
- `HiCRenderer.java` / `HiCColorScale.java`：三角形与方形绘制、RdYlBu_r 配色；
- `HiCTrack.java`：IGV 轨道封装（右键菜单、session 持久化）；
- `TrackLoader`：`.h5` / `.cool` / `.mcool` 走 Hi-C 分支。

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

## Sashimi 图（sashimi plot）增强

打开 Sashimi 剪接图（菜单 Tools → Sashimi Plot 或对应入口）后新增两项：

### Ctrl + 滚轮缩放
原先只能用顶部的 `+/-` 按钮按固定步长缩放。现在在**剪接弧区**或**下方基因结构区**按住
**Ctrl（Mac 上为 ⌘/Meta）+ 滚轮** 即可缩放，缩放以**光标位置为中心**（"zoom to cursor"），
滚动多格可连续放大/缩小，不再受限于固定的 `+/-` 步长。

- 没有 Ctrl/Meta 时，滚轮在基因结构区仍按原行为**纵向滚动**该区域；在剪接弧区则不触发任何动作。

### 基因结构背景色可调
下方基因结构（默认灰色）区域的背景色现在可配置：

- **右键基因结构轨道 → Set Background Color…** 直接选色，立即生效并记住；
- 也可在全局 **Preferences → RNA → Sashimi Plot → Gene structure background color** 里设置
  （默认浅灰 `240,240,240`）。

实现改动：

- `Constants` 新增 `SASHIMI_GENE_BACKGROUND_COLOR`；`preferences.tab` 在 `RNA` 分节下新增
  `##Sashimi Plot` 子节与该项（默认 `240,240,240`，格式 `R,G,B`）。
- `SashimiPlot`：构造里读取该偏好并应用到基因 `TrackComponent` 与滚动面板 viewport 背景
  （`applyGeneBackground()`）；新增 `addWheelZoomListeners()` 在剪接区与基因区挂 `MouseWheelListener`，
  Ctrl/Meta 时调用 `referenceFrame.doIncrementZoomAtCursor(...)` 以光标为中心缩放。
- `GeneTrackMouseAdapter.getPopupMenu()` 在基因轨道右键菜单（显示模式项之后）新增
  "Set Background Color…" 项，用颜色选择器选色后写入偏好并即时刷新。

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
