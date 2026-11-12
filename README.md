# myigv

[![Build](https://github.com/junjunlab/myigv/actions/workflows/gradle.yml/badge.svg)](https://github.com/junjunlab/myigv/actions/workflows/gradle.yml)
[![Release](https://img.shields.io/github/v/release/junjunlab/myigv)](https://github.com/junjunlab/myigv/releases)
[![Downloads](https://img.shields.io/github/downloads/junjunlab/myigv/total)](https://github.com/junjunlab/myigv/releases)
[![License](https://img.shields.io/github/license/junjunlab/myigv)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://www.oracle.com/java/technologies/)
[![Based on](https://img.shields.io/badge/based%20on-IGV%202.19.8-orange)](https://github.com/igvteam/igv)
[![Documentation](https://readthedocs.org/projects/myigv/badge/?version=latest)](https://myigv.readthedocs.io/en/latest/?badge=latest)

> A customized [IGV](https://github.com/igvteam/igv) genome browser: offline annotations, multi-gene panels, GTF-based gene search, auto-indexing, per-panel autoscale, Hi-C / BEDPE / TAD overlays, and SSH/SFTP remote browsing.

**myigv** is built on **IGV 2.19.8** and keeps all of IGV's capabilities, with extra enhancements aimed at four common workflows: **offline use**, **multi-gene comparison**, **Hi-C chromatin conformation**, and **SSH/SFTP remote data**. It works with RNA-seq / ChIP-seq / bigWig / Hi-C tracks and can read files directly from a server.

## Improvements over upstream IGV

| # | Improvement | Summary |
|---|-------------|---------|
| 1 | Offline annotations | 18 Hosted Genomes annotation resources pre-bundled (~86 MB); reference sequences still fetched online on demand |
| 2 | Multi-gene faceting | Comma/semicolon-separated genes in the search box → one panel per gene |
| 3 | GTF as search source | Pick a GTF/GFF from the dropdown; search matches only genes in that file |
| 4 | Auto sort + index | GTF/FASTA sorted and indexed on import, no dialogs, original files untouched |
| 5 | Canonical gene-name fill | Search box shows the canonical gene name (not coordinates) and lets you append more |
| 6 | Drop GTF solid bars | Filters exon-less `gene` container records; only real transcripts remain |
| 7 | Per-panel Group Autoscale | Each facet autoscales independently |
| 8 | Ctrl+wheel zoom | Zoom anchored at the cursor; the locus under the pointer stays put |
| 9 | Adjustable axis label | Y-axis `[min – max]` font size and top-left / top-right position |
| 10 | One-click installer | GitHub Actions builds Windows `.exe` / macOS `.dmg` |
| 11 | Read compressed files | `.gtf.gz` / `.fa.gz` auto-decompress once; no manual gunzip |
| 12 | Toggle transcript labels | Expanded view can show gene name / transcript ID / both |
| 13 | Hi-C matrix tracks | HiCExplorer `.h5`, cooler `.cool`/`.mcool`, Juicer `.hic` (`.gz` ok); triangle or square, multi-resolution |
| 14 | TAD outline overlay | Draw BED domains on the matrix with per-file line width, style, color, fill |
| 15 | BEDPE arc tracks | loops / arcs as circular, elliptical, or Bézier arcs |
| 16 | SSH/SFTP remote browse | Connect to an SSH server, browse, and read tracks (incl. Hi-C) directly; credentials stored with OS-level encryption |

> **Full, per-feature documentation** — behaviour, menus, and caveats for every item above — is on the [myIGV User Guide](https://myigv.readthedocs.io/en/latest/). This README is only a summary; an in-repo change log is in [`README-myigv.md`](README-myigv.md).

## Documentation

Full, illustrated guides for every feature above — the circular view, Hi-C / TAD overlays, BEDPE arcs, SSH/SFTP remote data, the parameter reference, and export — are published on **Read the Docs**:

**➜ [myIGV User Guide](https://myigv.readthedocs.io/en/latest/)**

The guide documents only what myIGV adds on top of upstream IGV 2.19.8; for stock IGV concepts, see the [official IGV documentation](https://igv.org/doc/).

## Quick start

### Using the installer

Download the matching artifact from **Actions → Build** (or a **Release**):

- Windows: run `myigv-1.0.2.exe`;
- macOS: open `myigv-1.0.2.dmg` and drag myigv into Applications.

The installer bundles the Java runtime and offline annotations, so no separate Java install is needed.

> **macOS: blocked on first launch?** Gatekeeper blocks all unnotarized third-party apps. On macOS 15+, click **Done**, then *System Settings → Privacy & Security → Open Anyway*. On older macOS, right-click (or Control-click) myigv → **Open**. Or run once:
>
> ```bash
> xattr -dr com.apple.quarantine /Applications/myigv.app
> open /Applications/myigv.app
> ```

### Build from source

Requires **JDK 21**:

```bash
./gradlew createDist          # Windows: gradlew.bat createDist
```

Output is in `build/myigv-dist/`; launch with:

```bash
build/myigv-dist/myigv.bat    # Windows
build/myigv-dist/myigv.sh     # Linux / macOS
```

## Relationship with upstream IGV

- Based on **IGV 2.19.8**, under the MIT license (see `LICENSE`).
- The upstream README is kept at [`README-IGV.md`](README-IGV.md).
- Detailed, item-by-item implementation notes and change log are in [`README-myigv.md`](README-myigv.md).

## License

MIT
