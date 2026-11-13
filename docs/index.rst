myIGV User Guide
================

**myIGV** is a desktop genome browser built from the sources of the
`Integrative Genomics Viewer (IGV) 2.19.8 <https://igv.org/doc/desktop/>`_.
It keeps all of upstream IGV's behaviour and adds a set of capabilities of its
own: an offline annotation pack, automatic sorting and indexing, gene-search
enhancements, native Hi-C contact-matrix tracks, SSH/SFTP remote browsing, and
a substantially extended **Circular View** with circlize-style genomic
heatmaps, ribbon links and labelled gene rings.

.. important::

   This guide documents **only the features myIGV adds on top of upstream IGV
   2.19.8**.  Standard IGV concepts — loading a genome, loading tracks,
   sessions, the Regions panel, preferences, and so on — are deliberately not
   repeated here.  Use the `official IGV desktop documentation
   <https://igv.org/doc/desktop/>`_ for those.

.. note::

   The installers published with **v1.0.2** correspond to an earlier revision
   of the source tree (tag date 2026-11-10).  The Circular View chapters of
   this guide describe the current source tree, which contains 78 further
   commits.  To obtain every feature described here, build from source with
   ``./gradlew createDist`` (see :doc:`installation`) or use a current build.

Where to start
--------------

.. list-table::
   :header-rows: 1
   :widths: 32 68

   * - If you want to …
     - Read
   * - See the complete list of additions over upstream IGV
     - :doc:`whats_new`
   * - Install myIGV and launch it
     - :doc:`installation`
   * - Work offline, or understand why sequence still needs the network
     - :doc:`offline_resources`
   * - Stop sorting/indexing files by hand
     - :doc:`auto_indexing`
   * - Search several genes at once, or search against your own GTF
     - :doc:`search`
   * - Load ``.hic`` / ``.cool`` / ``.mcool`` / ``.h5`` contact matrices
     - :doc:`hic`
   * - Use Ctrl+wheel zoom, Y-axis labels, per-panel group autoscale
     - :doc:`view_interactions`
   * - Use BEDPE arcs, transcript labels, or remote SSH/SFTP files
     - :doc:`desktop_extras`
   * - Use the Sashimi plot additions
     - :doc:`sashimi`
   * - Draw a whole-genome circos-style figure
     - :doc:`circular_overview`
   * - Look up any single parameter and its default
     - :doc:`parameter_reference`
   * - Browse every screenshot used in this guide
     - :doc:`screenshots`
   * - See which real data files every screenshot comes from
     - :doc:`real_data_examples`

Document map
------------

.. toctree::
   :maxdepth: 2
   :caption: Getting started

   whats_new
   installation

.. toctree::
   :maxdepth: 2
   :caption: Desktop enhancements

   offline_resources
   auto_indexing
   search
   hic
   view_interactions
   desktop_extras
   sashimi

.. toctree::
   :maxdepth: 2
   :caption: Circular View

   circular_overview
   circular_import
   circular_data_tracks
   circular_links
   circular_heatmap
   circular_labels
   circular_view_options
   circular_hic
   circular_export

.. toctree::
   :maxdepth: 1
   :caption: Reference

   parameter_reference
   real_data_examples
   screenshots

Conventions used in this guide
------------------------------

* ``Monospaced text`` denotes a file name, a JVM property, a value, or a piece
  of source code.
* ``Menu → Submenu → Item…`` denotes a sequence of UI selections.  Labels are
  quoted verbatim as they appear in the application, including capitalisation
  and the ellipsis character ``…``.
* Parameter tables always give the **default** and the **accepted range**,
  because most of these values are entered into free-text dialogs that clamp
  out-of-range input silently.
* Screenshots in this guide were captured from the headless render/verification
  harness in ``build/check/exports/`` and copied into ``docs/images/``.  They
  show the real renderer output, not mock-ups.

Indices and tables
------------------

* :ref:`genindex`
* Use the search field in the left sidebar to find a parameter by name.
  :doc:`parameter_reference` lists every default in one place.
