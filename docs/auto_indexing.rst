===============================
Automatic sorting and indexing
===============================

Upstream IGV 2.19.8 expects you to prepare many local data files by hand: run
``igvtools`` to sort feature files, create ``.idx`` indexes, place a ``.bai``
next to every BAM, and answer "create index?" / "file must be sorted" dialogs.
``myIGV`` removes that whole class of friction — sorting and indexing now
happen automatically and silently at load time. This is an addition over
upstream IGV 2.19.8.

The automatic behaviour is implemented by ``AutoIndexer`` (sort-check, sorting
via ``SorterFactory``, tribble indexing), ``GzipUtils`` (gzip / BGZF handling),
``BamIndexBuilder`` (BAM indexing), and integrated into ``TrackLoader.load``,
``FastaUtils.ensureIndexed`` / ``FastaGenomeLoader``, ``GenomeLoader.getLoader``
and ``SamReaderPool.getIndexPath``.

GTF / GFF / GFF3 / GVF auto sort-check and indexing
----------------------------------------------------

For a **local, uncompressed** GTF, GFF, GFF3 or GVF file, loading triggers two
automatic steps:

#. **Sort check.** ``myIGV`` verifies whether records are ordered by
   *chromosome then start position*. If the file is out of order, a sorted copy
   named ``xxx.sorted.gtf`` (with the matching extension) is written next to
   the original and loaded instead. **The original file is never modified.**
   This matters for Ensembl / GENCODE GTF files: they are sorted by gene start,
   but transcript start positions jump back and forth inside a gene, so htsjdk
   cannot index them directly.
#. **Index build.** If no ``.idx`` tribble index exists, one is created
   automatically.

During this work the status bar shows ``Sorting ...`` and ``Indexing ...``. The
track name still shows your original file name, even when a sorted copy is what
is actually loaded. The sorted copy and the index are generated once and
reused; importing the same file again does not repeat the work.

.. note::

   The sorted copy is a separate file. Your source GTF/GFF is left byte-for-byte
   untouched, so you can keep it under version control or share it without
   worrying about ``myIGV`` rewriting it.

FASTA auto ``.fai`` and regularisation
--------------------------------------

When a FASTA file is loaded **as a genome**, a ``.fai`` index has always been
created automatically. ``myIGV`` adds a fallback for a case that used to fail:
if the FASTA has **inconsistent line lengths** (which cannot be indexed
directly), it writes a normalised copy named ``xxx.regularized.fa`` with **80
bases per line** and builds the index for that copy instead. Previously this
situation produced an error and left you to fix the file yourself.

Compressed files are read directly
----------------------------------

Handling of ``.gz`` files is provided by ``org.broad.igv.util.GzipUtils``:

* ``.gtf.gz`` / ``.gff.gz`` are decompressed into a ``.gtf`` / ``.gff`` file in
  the **same directory** as the source (an existing decompressed file is reused
  when it is newer), then sorted and indexed as above;
* ``.fa.gz`` / ``.fasta.gz`` are likewise decompressed to ``.fa`` before the
  ``.fai`` index is built;
* files that are already **bgzip + tabix** (``.tbi``), or **bgzip + ``.gzi`` /
  ``.fai``**, are *not* decompressed — they are read directly through their
  indexes;
* decompression is done with a **temporary file plus atomic replace**, so a
  cancelled operation never leaves a half-written file;
* the original compressed file is never modified.

If the directory is read-only or a write fails for any other reason, ``myIGV``
only logs the problem and loads the file as normal — it does not abort the
load.

.. tip::

   To turn off all automatic sorting, indexing, and decompression, add
   ``-Dmyigv.autoIndex=false`` to the JVM startup options, or set the
   environment variable ``MYIGV_AUTO_INDEX=false``. See
   :doc:`offline_resources` for how these switches relate to the offline pack.

Automatic BAM index creation
----------------------------

Upstream IGV 2.19.8 requires a ``.bai`` (or ``.csi``) next to a BAM and, if it
is missing, pops a dialog asking you to type an index path. ``myIGV`` instead
**builds the index in place**:

* when a local ``.bam`` has no ``.bai`` / ``.csi``, it generates
  ``<file>.bam.bai`` in the same directory and loads the BAM immediately,
  without interrupting your work;
* the index is written to a temporary file and only **atomically replaced on
  success** — cancelling or a failure leaves no partial ``.bai``;
* the status bar shows ``No index found - creating xxx.bam.bai ...`` while it
  works and ``BAM index created: <path>`` when done;
* when the same file is loaded concurrently by several tracks, a **lock
  serialises** the build so it is never created twice.

Failure dialogs
~~~~~~~~~~~~~~~

If indexing cannot succeed (BAM not coordinate-sorted, directory not writable,
etc.), a dialog explains the specific reason and still offers the manual
"enter index path" entry point. It replaces the old generic
``Index is required, but no index found.`` message:

* **Not sorted:** ``BAM file is not coordinate sorted (header sort order = ...)``
  — you must run ``samtools sort`` first.
* **Remote BAM (http/https):** an index cannot be built locally; the dialog
  says so explicitly and asks you to supply an index URL.

.. note::

   Benchmark from the project: a 272 MB STAR-output BAM was indexed in about
   **12 seconds**, producing a **263 KB** index, after which coordinate queries
   worked normally.

BED ``file:`` URI fix for Windows
---------------------------------

``TribbleFeatureSource.toReaderPath`` now converts local BED paths into a
``file:`` URI before handing them to htsjdk. This fixes the
``No FileSystemProvider`` / ``Illegal char <:>`` errors that occurred when an
absolute Windows path (for example ``C:\data\peaks.bed``) was supplied on the
command line or through a port. It is a correctness fix that accompanies the
auto-indexing work.

Switches and generated filenames
--------------------------------

The table below summarises every switch and every generated filename pattern.
"Original untouched" is a deliberate guarantee of this feature.

.. list-table:: Switches and generated files
   :header-rows: 1
   :widths: 30 20 18 32

   * - Option / pattern
     - Values / applies to
     - Default
     - What it does
   * - ``-Dmyigv.autoIndex=false``
     - ``true`` / ``false``
     - ``true``
     - Disables all automatic sorting, indexing, regularisation and
       gzip decompression for feature/genome files.
   * - ``MYIGV_AUTO_INDEX=false``
     - ``true`` / ``false``
     - ``true``
     - Environment-variable equivalent of the JVM property above.
   * - ``xxx.sorted.gtf`` (also ``.gff`` / ``.gff3`` / ``.gvf``)
     - out-of-order GTF/GFF/GFF3/GVF
     - created on demand
     - Sorted copy written next to the source and loaded instead; the original
       file is never changed. Generated once, then reused.
   * - ``xxx.idx``
     - feature files
     - created on demand
     - Tribble index built automatically when missing.
   * - ``xxx.regularized.fa``
     - FASTA with inconsistent line lengths
     - created on demand
     - Normalised copy with 80 bases per line, indexed in place of the source.
   * - ``xxx.fai``
     - FASTA genomes
     - created on demand
     - FASTA sequence index, built silently.
   * - ``<file>.bam.bai``
     - local BAM without a ``.bai`` / ``.csi``
     - created on demand
     - BAM index built in place via a temp file + atomic replace.
   * - ``.gtf`` / ``.gff`` / ``.fa`` (decompressed)
     - ``.gtf.gz`` / ``.gff.gz`` / ``.fa.gz`` / ``.fasta.gz``
     - created on demand
     - Decompressed copy in the same directory (reused when newer); original
       ``.gz`` is untouched. bgzip+tabix / bgzip+``.gzi`` files are read
       directly instead.

Implementation classes
-----------------------

For developers, the automatic behaviour is wired through these classes:

* ``org.broad.igv.util.AutoIndexer`` (new) — disorder detection, sorting via
  ``SorterFactory``, tribble index creation;
* ``TrackLoader.load`` — calls ``AutoIndexer.ensureSortedAndIndexed`` before
  loading and, if needed, points the locator at the sorted copy while keeping
  the original file name as the track name;
* ``FastaUtils.ensureIndexed`` + ``FastaGenomeLoader`` — silently build
  ``.fai``, regularise and retry when line lengths are inconsistent;
* ``org.broad.igv.util.GzipUtils`` (new) — detect gzip / BGZF, decompress into a
  same-directory file (reuse newer copy, atomic replace);
* ``GenomeLoader.getLoader`` — ``.fa.gz`` is no longer rejected outright; it is
  decompressed first and then follows the FASTA indexing flow;
* ``org.broad.igv.sam.reader.BamIndexBuilder`` (new) — builds the BAM index
  with htsjdk ``BAMIndexer`` using temp-file + atomic replace, and validates the
  sort order;
* ``SamReaderPool.getIndexPath`` — for a local BAM with no index, attempts
  automatic creation first, and only falls back to the "enter index path"
  dialog on failure, with a message that explains the reason;
* ``TribbleFeatureSource.toReaderPath`` — converts local BED paths to ``file:``
  URIs (the Windows-path fix).
