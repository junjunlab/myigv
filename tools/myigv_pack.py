#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
myigv 本地资源包生成脚本 (IGV 2.19.8 适配)

把 IGV 打开时本要联网下载的「注释类」资源预先下载到 pack/ 目录，并生成 manifest.tsv，
使 myigv 在断网/打开时直接从本地读取。

打包范围（Hosted Genomes 对话框里的 18 个基因组）：
  ce10 ce11 dm3 dm6 NC_000913.3 NC_008601 hg18 hg19 hg38_1kg hg38
  NC_012920 mm9 mm10 rn6 rn7 sacCer3 ASM294v2 danRer11

每个基因组打包：
  - 基因组描述配置 (.json / .gbk)
  - 染色体大小 / 染色体带 / 别名 等辅助文件
  - 默认自动加载的注释轨道 (如 hg38 的 Refseq All)
  - MANE / rsdb 等搜索索引 (.bb / .ix)
  - hub 索引文件 (hub.txt / genomes.txt / trackDb.txt)，仅让 Tracks 菜单离线可列目录

不打包：参考基因组序列 (.fasta / .2bit)，序列仍走联网（按需求放大后才取）。

用法：
  python tools/myigv_pack.py            # 增量打包（已有文件跳过）
  python tools/myigv_pack.py --clean    # 清空 pack/ 后重新下载

依赖：python3 + curl（比 urllib 更稳，遇到 GitHub 偶发 TLS 断连会重试）
"""

import os
import re
import shutil
import subprocess
import sys
import time
import json
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, "pack")
MANIFEST = os.path.join(PACK, "manifest.tsv")

USER_AGENT = "IGV/2.19.8"
TIMEOUT = 240
RETRIES = 6
LIST_URL = "https://igv.org/genomes/genomes2.tsv"

# 18 个目标基因组（与 2.19.8 的 genomes2.tsv 中的 ID 对齐）
TARGET_GENOMES = [
    "ce10", "ce11", "dm3", "dm6",
    "NC_000913.3", "NC_008601", "NC_012920",
    "hg18", "hg19", "hg38_1kg", "hg38",
    "mm9", "mm10", "rn6", "rn7",
    "sacCer3", "ASM294v2", "danRer11",
]

# 基因组配置里需要一并下载的辅助字段
AUX_KEYS = [
    "chromSizesURL", "cytobandURL", "aliasURL", "indexURL",
    "maneBbURL", "maneTrixURL", "rsdbURL",
    "refseqBbURL", "geneBbURL", "gencodeBbURL",
]

# 额外固定下载的资源（基因组下拉列表 / URL 映射）
EXTRA_LISTS = [
    "https://igv.org/genomes/genomes2.tsv",
    "https://igv.org/genomes/genomes.txt",
    "https://raw.githubusercontent.com/igvteam/igv-data/refs/heads/main/data/url_mappings.tsv",
]

# Gene tracks that the UCSC hub offers and the app can auto-select ("Refseq All" / "Refseq
# Curated"), but which the genome JSON's own "tracks" list does not mention. Without these the
# circular view has no genes to draw for those species when offline. dm6 is the common case:
# its JSON lists only Refseq Curated, while the UI picks Refseq All from the hub.
EXTRA_TRACKS = [
    "https://hgdownload.soe.ucsc.edu/goldenPath/dm6/database/ncbiRefSeq.txt.gz",
    "https://hgdownload.soe.ucsc.edu/goldenPath/ce11/database/ncbiRefSeq.txt.gz",
    "https://hgdownload.soe.ucsc.edu/goldenPath/sacCer3/database/ncbiRefSeqCurated.txt.gz",
    "https://hgdownload.soe.ucsc.edu/goldenPath/rn7/database/ncbiRefSeqCurated.txt.gz",
]


def fetch(url, dest):
    """用 curl 下载 url 到 dest，命中已有文件则跳过。返回是否成功。"""
    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        return True
    parent = os.path.dirname(dest)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    for attempt in range(1, RETRIES + 1):
        try:
            cmd = [
                "curl", "-sSL", "--max-time", str(TIMEOUT),
                "-A", USER_AGENT, "-o", dest, url,
            ]
            r = subprocess.run(cmd, capture_output=True)
            if r.returncode == 0 and os.path.isfile(dest) and os.path.getsize(dest) > 0:
                return True
            if attempt < RETRIES:
                time.sleep(2 * attempt)
        except Exception:
            if attempt < RETRIES:
                time.sleep(2 * attempt)
    if os.path.isfile(dest):
        os.remove(dest)
    print("  !! 下载失败: %s" % url)
    return False


def relative_for(url):
    """根据 URL 计算相对 pack 根的路径（统一用 /，跨平台）。

    所有文件都加 URL 短哈希前缀，避免同名文件（不同基因组的 ncbiRefSeq.txt.gz、
    不同 hub 的 genomes.txt / hub.txt / trackDb.txt 等）互相覆盖导致数据错乱。
    应用只通过 manifest 的 URL->本地文件映射读取，文件名本身无意义。
    """
    import hashlib
    name = url.split("?")[0].rstrip("/").split("/")[-1]
    base = os.path.basename(name)
    folder = "genomes" if (base.endswith(".json") or base.endswith(".gbk")) else "data"
    h = hashlib.md5(url.encode("utf-8")).hexdigest()[:10]
    return folder + "/" + h + "-" + base


def find_existing(basename):
    """在 pack/ 里找一个已存在、文件名以 -basename 结尾或恰好等于 basename 的文件，用于复用。"""
    for sub in ("data", "genomes"):
        d = os.path.join(PACK, sub)
        if not os.path.isdir(d):
            continue
        for fn in os.listdir(d):
            if fn == basename or fn.endswith("-" + basename):
                return os.path.join(d, fn)
    return None


def add(entries, url):
    if not url:
        return
    url = url.strip()
    if not url or url.startswith("file:") or url.startswith("data:"):
        return
    rel = relative_for(url)
    dest = os.path.join(PACK, *rel.split("/"))
    # 复用已有同名文件
    if not (os.path.isfile(dest) and os.path.getsize(dest) > 0):
        existing = find_existing(os.path.basename(rel))
        if existing and not os.path.abspath(existing) == os.path.abspath(dest):
            shutil.copyfile(existing, dest)
    ok = fetch(url, dest)
    if ok and os.path.isfile(dest) and os.path.getsize(dest) > 0:
        entries[url] = rel


def read_text(url):
    dest = os.path.join(PACK, "data", "_tmp_" + os.path.basename(url.split("?")[0]))
    text = None
    if fetch(url, dest):
        try:
            with open(dest, "r", encoding="utf-8", errors="replace") as f:
                text = f.read()
        except Exception:
            text = None
    if os.path.isfile(dest):
        os.remove(dest)
    return text


def parse_list(list_text):
    """解析 genomes2.tsv: name \t config_url \t genome_id"""
    mapping = {}
    for line in list_text.splitlines():
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 3:
            mapping[parts[2].strip()] = parts[1].strip()
    return mapping


def hub_index_files(hub_url, genome_id):
    """给定 hub.txt 地址，返回其 genomes.txt 与 trackDb.txt 的 URL。"""
    out = [hub_url]
    text = read_text(hub_url)
    if not text:
        return out
    base = hub_url.rsplit("/", 1)[0] + "/"
    genomes_url = None
    stanza = {}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            if stanza.get("genomesFile"):
                genomes_url = stanza["genomesFile"]
            stanza = {}
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            stanza[parts[0]] = parts[1].strip()
    if stanza.get("genomesFile"):
        genomes_url = stanza["genomesFile"]
    if not genomes_url:
        return out
    genomes_url = urllib.parse.urljoin(base, genomes_url)
    out.append(genomes_url)
    gtext = read_text(genomes_url)
    if not gtext:
        return out
    gbase = genomes_url.rsplit("/", 1)[0] + "/"
    wanted = []
    stanza = {}
    for line in gtext.splitlines() + [""]:
        line = line.strip()
        if not line:
            if stanza:
                if stanza.get("genome") == genome_id or not any(s.get("genome") == genome_id for s in wanted):
                    if stanza.get("trackDb"):
                        wanted.append(stanza)
                stanza = {}
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            stanza[parts[0]] = parts[1].strip()
    seen = set()
    for item in wanted:
        track_db = urllib.parse.urljoin(gbase, item["trackDb"])
        if track_db not in seen:
            seen.add(track_db)
            out.append(track_db)
    return out


def bundle_genome(entries, genome_id, config_url, with_hub_index):
    print("== %s  (%s)" % (genome_id, config_url))
    add(entries, config_url)
    if config_url.endswith(".gbk"):
        return  # Genbank 基因组的注释内嵌在序列文件本身，无其它可下载资源
    text = read_text(config_url)
    if not text:
        print("  !! 无法读取配置: %s" % config_url)
        return
    try:
        config = json.loads(text)
    except Exception as e:
        print("  !! 配置解析失败: %s  (%s)" % (config_url, e))
        return
    for key in AUX_KEYS:
        add(entries, config.get(key))
    for track in config.get("tracks", []):
        add(entries, track.get("url"))
        add(entries, track.get("indexURL"))
        add(entries, track.get("trixURL"))
    if with_hub_index:
        for hub in config.get("hubs", []):
            for url in hub_index_files(hub, genome_id):
                add(entries, url)


def main():
    clean = "--clean" in sys.argv

    # Resolve the genome list BEFORE touching pack/: igv.org/genomes/genomes2.tsv can answer 403,
    # and deleting first would leave an empty pack with nothing to rebuild from (the app would then
    # have no genome configs at all). Fall back to the per-genome JSON URLs, which stay reachable.
    list_text = read_text(LIST_URL)
    mapping = parse_list(list_text) if list_text else {}
    if not mapping:
        print("警告：基因组清单 %s 不可用，改用 https://igv.org/genomes/json/<id>.json 直连"
              % LIST_URL)
        mapping = {gid: "https://igv.org/genomes/json/%s.json" % gid
                   for gid in TARGET_GENOMES}
    missing = [g for g in TARGET_GENOMES if g not in mapping]
    if missing:
        print("警告：以下基因组不在清单中，将被跳过: %s" % ", ".join(missing))

    if clean and os.path.isdir(PACK):
        print("清空 pack/ ...")
        shutil.rmtree(PACK)
    os.makedirs(os.path.join(PACK, "data"), exist_ok=True)
    os.makedirs(os.path.join(PACK, "genomes"), exist_ok=True)

    entries = {}
    # 固定列表资源
    for url in EXTRA_LISTS:
        add(entries, url)

    # hub 里由 app 自动选中的基因轨道（见 EXTRA_TRACKS 注释）
    for url in EXTRA_TRACKS:
        add(entries, url)

    for gid in TARGET_GENOMES:
        url = mapping.get(gid)
        if url:
            bundle_genome(entries, gid, url, with_hub_index=True)

    # 写 manifest
    with open(MANIFEST, "w", encoding="utf-8") as f:
        f.write("# myigv local resource pack manifest\n")
        f.write("# original_url\tlocal_relative_path\n")
        for url, rel in sorted(entries.items()):
            f.write("%s\t%s\n" % (url, rel))

    print("\n完成：%d 个资源，清单 %s" % (len(entries), MANIFEST))
    total = sum(os.path.getsize(os.path.join(PACK, rel)) for rel in entries.values())
    print("资源总大小: %.1f MB" % (total / 1048576.0))


if __name__ == "__main__":
    main()
