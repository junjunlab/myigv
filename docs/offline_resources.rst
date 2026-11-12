============================
Offline annotation resources
============================

``myIGV`` is built on the **IGV 2.19.8** source. Upstream IGV downloads its
annotation-type resources on demand whenever it starts or loads a genome, which
means a genome browser that is offline or on a weak network often fails to show
genomes and annotation tracks. ``myIGV`` pre-packages those annotation
resources into the distribution so that genomes and their default annotations
load correctly without a network connection.

A total of **119 resources (about 86 MB)** are bundled. This is an addition
over upstream IGV 2.19.8, which fetches each of these files over the network at
run time.

.. note::

   The **reference genome sequence itself** (``.fa`` / ``.fasta`` / ``.2bit``)
   is deliberately *not* bundled. It is still fetched on demand, but only when
   you zoom in far enough to display individual bases. This is intentional and
   is not an omission — see "Still online" below.

What is bundled
---------------

The following resources are packaged and available offline:

* the **18 hosted-genome configurations** shown in the *Hosted Genomes* dialog
  (``.json`` / ``.gbk`` files);
* chromosome sizes, cytoBand (chromosome banding), aliases, and ``chromAlias``
  helper files;
* the **default annotation tracks that are loaded automatically** — for
  example, for hg38 these include ``ncbiRefSeq`` (Refseq All), ``MANE``
  (``mane.1.0.bb`` / ``.ix``), and ``rsdb`` (``refSeqHistorical.bb``);
* the genome drop-down list and track-hub index files — ``genomes2.tsv``,
  ``genomes.txt``, ``url_mappings.tsv``, plus each hub's ``hub.txt``,
  ``genomes.txt`` and ``trackDb.txt``. Together these let the ``Tracks`` menu
  list its directory of tracks even when offline.

.. tip::

   Because the genome list is local, ``myIGV`` also points its default genome
   at ``https://igv.org/genomes/json/hg38.json`` (a file that is inside the
   pack), so startup does not stall waiting on a GitHub-hosted genome list.

Still online (intentionally)
----------------------------

The following remain network-dependent, matching the original IGV behaviour:

* the **reference genome sequence** — ``.fa`` / ``.fasta`` / ``.2bit``. IGV only
  fetches it once you zoom in to the level where bases / sequence are shown.

Upstream IGV 2.19.8 behaves the same way here; ``myIGV`` keeps this behaviour
on purpose. Bundling full reference sequences would add hundreds of megabytes
per genome, and the sequence is only needed at high zoom levels. When offline,
you can still browse chromosomes, genes, and annotation tracks — you simply do
not see the raw nucleotide letters at base-level zoom.

Data-directory isolation
------------------------

``myIGV`` uses the ``-Dmyigv.dir`` JVM property to point its own data directory
at ``build/myigv-dist/myigv_data`` inside the distribution. It therefore **does
not share ``~/.igv`` with a system-installed IGV**. This gives two benefits:

#. it never reads leftover IGV caches (for example an old GitHub genome list),
   so the offline behaviour stays predictable;
#. it never pollutes your local IGV preferences and logs.

Resource-pack mechanics
-----------------------

The offline pack is a directory named ``pack/`` whose core class is
``org.broad.igv.local.LocalResourcePack``. It reads ``pack/manifest.tsv`` — a
tab-separated file mapping each **original URL** to a **local relative path** —
and performs the URL-to-local-file mapping at the I/O boundary. It does not
modify the original URL stored in ``ResourceLocator``; the I/O hooks are in
``ParsingUtils``, ``IGVSeekableStreamFactory`` and ``HttpUtils`` (read,
connect, size, availability).

``LocalResourcePack`` looks for the pack in several layouts —
``app-content/pack``, ``app/pack``, ``<root>/pack`` — so that the directory
layout produced by ``jpackage`` is recognised automatically. You normally do
not need to pass ``-Dmyigv.pack`` explicitly.

You regenerate the pack with ``tools/myigv_pack.py``:

.. code-block:: bash

   python tools/myigv_pack.py            # incremental: existing files are skipped
   python tools/myigv_pack.py --clean    # clear pack/ and re-download everything

After changing ``pack/``, rebuild the distribution with ``gradlew createDist``,
which copies ``pack/`` into ``build/myigv-dist/pack``.

.. note::

   Incremental mode (no flags) skips files that are already present, so it is
   fast for routine updates. Use ``--clean`` only when you want to force a full
   re-download of every annotation resource.

Switches
--------

The following table lists the JVM properties and environment variables related
to the offline pack and data directory. The two ``autoIndex`` entries belong to
the automatic sorting/indexing feature and are documented in full at
:doc:`auto_indexing`.

.. list-table:: Offline-pack and data-directory switches
   :header-rows: 1
   :widths: 26 20 18 36

   * - Option
     - Values
     - Default
     - What it does
   * - ``-Dmyigv.dir``
     - a directory path
     - ``build/myigv-dist/myigv_data``
     - Overrides the user data directory so ``myIGV`` is isolated from the
       system IGV ``~/.igv`` directory. Prevents reading stale caches and
       avoids polluting IGV preferences/logs.
   * - ``-Dmyigv.pack``
     - a directory path
     - auto-detected
     - Forces the location of the offline ``pack/`` directory. Normally not
       needed because ``LocalResourcePack`` searches ``app-content/pack``,
       ``app/pack`` and ``<root>/pack`` automatically.
   * - ``-Dmyigv.autoIndex=false``
     - ``true`` / ``false``
     - ``true``
     - Disables automatic sorting and indexing of feature/genome files. This
       belongs to auto-indexing — see :doc:`auto_indexing`.
   * - ``MYIGV_AUTO_INDEX=false``
     - ``true`` / ``false``
     - ``true``
     - Environment-variable equivalent of ``-Dmyigv.autoIndex=false``. This
       belongs to auto-indexing — see :doc:`auto_indexing`.
