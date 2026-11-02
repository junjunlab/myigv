# myigv

> A customized IGV genome browser: offline annotations, multi-gene panels, GTF-based gene search, auto-indexing and per-panel autoscale.

**myigv** 基于 [IGV 2.19.8](https://github.com/igvteam/igv) 定制，保留 IGV 的全部能力，并针对
**离线使用**与**多基因对比**两个高频场景做了增强。适用于 RNA-seq / ChIP-seq / bigWig 等常见可视化。

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

---

## 快速开始

### 使用安装包

到 **Actions → Build installers** 下载对应 Artifact（或从 Release 下载）：

- Windows：运行 `myigv-1.0.0.exe` 安装；
- macOS：打开 `myigv-1.0.0.dmg`，把 myigv 拖入 Applications。

安装包已自带运行时与离线注释，装完即用，无需另装 Java。

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
