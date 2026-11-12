What myIGV adds over upstream IGV
=================================

myIGV is built on **IGV 2.19.8**.  Everything in upstream IGV still works; the
table below lists the additions, and the second table lists the Circular View
work that is newer still.  This is the scope of this guide: stock IGV features
are not documented here.

Feature overview
----------------

.. list-table::
   :header-rows: 1
   :widths: 5 27 48 20

   * - #
     - Addition
     - What upstream IGV 2.19.8 does / what myIGV changes
     - Documented in
   * - 1
     - Offline annotation pack
     - IGV downloads hosted-genome and annotation resources on first use. myIGV
       ships 119 of them (~86 MB) inside the installer and maps the URLs to
       local files. Reference sequence is deliberately still fetched online.
     - :doc:`offline_resources`
   * - 2
     - Multi-gene faceted search
     - The search box accepts comma/semicolon separated genes and loci and
       turns them into one Gene List with a panel per gene.
     - :doc:`search`
   * - 3
     - GTF/GFF as a search source
     - A dropdown next to the search box restricts candidate and committed
       searches to the gene names in a loaded GTF/GFF/GFF3/GVF track.
     - :doc:`search`
   * - 4
     - Automatic sorting and indexing
     - IGV asks you to sort and index GTF/FASTA and to supply a BAM index.
       myIGV does it silently, in place, with the original files untouched.
     - :doc:`auto_indexing`
   * - 5
     - Canonical gene name in the search box
     - IGV replaces the searched text with coordinates after a jump. myIGV keeps
       the resolved gene name so you can append ``,GENE2``.
     - :doc:`search`
   * - 6
     - No "long solid bar" in GTF tracks
     - Structure-less ``gene`` container records are skipped so real transcript
       models (exon blocks, intron lines, strand arrows) stay visible.
     - :doc:`search`
   * - 7
     - Per-panel Group Autoscale
     - Group Autoscale is computed per gene-list facet instead of across the
       whole view; toggle with ``Group scale each gene panel``.
     - :doc:`view_interactions`
   * - 8
     - Ctrl/⌘ + wheel zoom to cursor
     - Upstream had the handler but a ``/ 2`` integer division made one wheel
       notch a no-op, and zoom was centred on the view. myIGV zooms by one
       level per notch around the genomic position under the pointer.
     - :doc:`view_interactions`
   * - 9
     - Configurable Y-axis range label
     - ``Y-Axis Range Label`` right-click submenu sets the ``[min - max]``
       label's font size and top-left / top-right position.
     - :doc:`view_interactions`
   * - 10
     - One-click installers
     - GitHub Actions produces a Windows ``.exe`` and a macOS ``.dmg`` with a
       bundled runtime and the offline pack.
     - :doc:`installation`
   * - 11
     - Compressed files read directly
     - ``.gtf.gz`` / ``.gff.gz`` / ``.fa.gz`` are decompressed once next to the
       source; bgzip+tabix files are read in place.
     - :doc:`auto_indexing`
   * - 12
     - Switchable transcript labels
     - ``Transcript label`` right-click submenu shows the gene name, the
       transcript accession, a readable transcript name, both, or a custom
       attribute field.
     - :doc:`desktop_extras`
   * - 13
     - Native Hi-C contact-matrix tracks
     - Direct read of HiCExplorer ``.h5``, cooler ``.cool`` / ``.mcool`` and
       Juicer ``.hic`` (plus gzip-wrapped variants), with triangle/square views,
       multi-resolution switching, colour transforms and a colour legend. No
       Python and no native libraries are required.
     - :doc:`hic`
   * - 14
     - TAD / domain overlays
     - BED tracks are drawn directly onto the Hi-C matrix with per-file line
       width, line style, line colour, fill colour and fill opacity.
     - :doc:`hic`
   * - 15
     - BEDPE arc tracks
     - Three arc shapes (circular, ellipse, bezier), proportional arcs driven
       by a numeric column, per-track styling, UTF-16 BOM tolerance and
       minimal 6-column support.
     - :doc:`desktop_extras`
   * - 16
     - SSH / SFTP remote browsing
     - ``File → Load from SSH Server...`` (shortcut ``H``) opens a remote file
       browser and reads tracks directly over SFTP, including seekable formats.
     - :doc:`desktop_extras`
   * - 17
     - Sashimi plot additions
     - Ctrl/⌘ + wheel zoom to cursor in the splice-arc and gene-structure
       areas, plus a configurable gene-structure background colour.
     - :doc:`sashimi`
   * - 18
     - Isolated user data directory
     - ``-Dmyigv.dir`` keeps myIGV's preferences and caches out of the system
       IGV ``~/.igv`` directory.
     - :doc:`offline_resources`

Circular View additions
-----------------------

Upstream IGV has no circlize-style whole-genome composition tooling.  myIGV
adds a Circular View and then extends it with the following.  The base ring was
introduced before v1.0.2; everything from ``Export`` onward is newer than the
v1.0.2 tag.

.. list-table::
   :header-rows: 1
   :widths: 30 50 20

   * - Addition
     - What it does
     - Documented in
   * - Whole-genome / single-chromosome ring
     - Ideogram ring with cytobands, a coordinate axis with major and minor
       ticks, chromosome labels, and configurable arc span and arc start.
     - :doc:`circular_view_options`
   * - Ring data bands
     - Loaded feature, data, gene and Hi-C tracks are stacked as concentric
       bands; the Data Tracks column order *is* the radial order.
     - :doc:`circular_data_tracks`
   * - ``Import ▾`` menu and circular-only layers
     - Links / Heatmap / Labels files are parsed into layers that exist only in
       the Circular View — never as IGV tracks, never in the horizontal view,
       never in a saved session.
     - :doc:`circular_import`
   * - Per-layer context menus
     - Right-clicking a layer row shows only the parameters relevant to that
       layer kind.
     - :doc:`circular_data_tracks`
   * - circlize-style ribbon links
     - Filled ribbons built exactly like circlize ``circos.link``: a polygon
       bounded by two quadratic Bezier edges that bow toward the centre and are
       closed by the two end arcs, so wide intervals give wide bands and 1 bp
       anchors taper to a tip.  Optional twist (``inverse``), crest,
       transparency and directional arrow heads.
     - :doc:`circular_links`
   * - Genomic heatmap ring
     - Equal-width cells (``posTransform.default``), 4-segment comb connectors
       (``circos.genomicPosTransformLines``), inside/outside placement, a
       diverging low/mid/high colour ramp and an out-of-ring colour legend.
     - :doc:`circular_heatmap`
   * - Genomic labels ring
     - Radial gene labels with comb connectors, circlize ``smartAlign`` for
       dense regions, inward reading, and full typographic control.  Outside
       mode leaves the ideogram radius, chromosome names and axis untouched.
       Inside mode places the ring like any band, so the labels row's position
       in the Data Tracks column decides its radius — drag it last and the
       labels become the innermost ring.
     - :doc:`circular_labels`
   * - Hi-C on the ring
     - Triangle/square Hi-C contact matrices warped into a ring, with TAD
       overlays, flip support, corner legends and a sector-bounded warp that
       keeps two loaded ``.hic`` files interactive.
     - :doc:`circular_hic`
   * - Publication export
     - ``Export ▾`` writes PNG, SVG or PDF at a chosen size and DPI through a
       single dialog.
     - :doc:`circular_export`
   * - Axis line thickness and inside clearance
     - ``Axis line thickness…`` controls the baseline and ticks; inside tick
       labels reserve radial room so they do not collide with data bands.
     - :doc:`circular_view_options`

.. note::

   **Two different ways to load links.**  A BEDPE file opened through
   ``File → Load from File...`` becomes a normal IGV interaction track, appears
   in the horizontal view, and is saved in sessions.  A links file opened
   through ``Import ▾ → Links  (BEDPE / Circos links)…`` becomes a
   *circular-only layer*.  The two paths intentionally coexist; see
   :doc:`circular_import`.

Related upstream documentation
------------------------------

* `IGV desktop documentation <https://igv.org/doc/desktop/>`_ — everything not
  listed above.
* `circlize <https://jokergoo.github.io/circlize_book/book/>`_ — the R package
  whose ``posTransform.default``, ``circos.genomicPosTransformLines``,
  ``circos.genomicHeatmap``, ``circos.genomicLabels``, ``smartAlign`` and
  ``circos.link`` semantics the Circular View reproduces.
