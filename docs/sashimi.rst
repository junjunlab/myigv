==========================
Sashimi plot enhancements
==========================

A Sashimi plot is opened through ``Tools → Sashimi Plot`` (or the equivalent
entry point). ``myIGV`` adds two refinements over upstream IGV 2.19.8: Ctrl +
wheel zoom and a configurable gene-structure background colour.

Ctrl + wheel zoom
-----------------

Previously you could only zoom a Sashimi plot with the top ``+/-`` buttons,
which move in fixed steps. Now, in **both the splice-arc area and the
lower gene-structure area**, hold **Ctrl (⌘ / Meta on macOS) + wheel** to zoom.
The zoom is **centred on the cursor** ("zoom-to-cursor"), and scrolling several
notches zooms continuously instead of being limited to fixed ``+/-`` steps.

* Without Ctrl/Meta, the wheel in the gene-structure area keeps its original
  behaviour of **scrolling that area vertically**;
* in the splice-arc area, a plain wheel does nothing.

Under the hood ``SashimiPlot.addWheelZoomListeners()`` attaches a
``MouseWheelListener`` to both the splice and gene areas; on Ctrl/Meta it calls
``referenceFrame.doIncrementZoomAtCursor(...)`` to zoom about the pointer — the
same ``ReferenceFrame`` helper used by the main view (see
:doc:`view_interactions`).

Gene-structure background colour
--------------------------------

The lower gene-structure area has a grey background by default. That colour is
now configurable in two places:

* **right-click the gene track → ``Set Background Color…``** — pick a colour;
  it applies immediately and is remembered;
* **``Preferences → RNA → Sashimi Plot → Gene structure background color``** —
  the global preference.

The default is the light grey ``240,240,240``, expressed as an ``R,G,B`` string.

.. list-table:: Sashimi gene-background parameters
   :header-rows: 1
   :widths: 34 22 18 26

   * - Option
     - Values
     - Default
     - What it does
   * - ``Set Background Color…`` (gene-track right-click menu)
     - any colour
     - ``240,240,240``
     - Sets the gene-structure background for this plot immediately and stores
       the choice.
   * - ``Preferences → RNA → Sashimi Plot → Gene structure background color``
     - an ``R,G,B`` string
     - ``240,240,240``
     - Global preference for the gene-structure background colour.
   * - Ctrl + wheel
     - zoom gesture
     - n/a
     - Zoom about the cursor in the arc and gene areas; plain wheel still
       scrolls the gene area and is inert in the arc area.

Implementation notes
--------------------

* ``Constants`` adds ``SASHIMI_GENE_BACKGROUND_COLOR``; ``preferences.tab`` gains
  a ``##Sashimi Plot`` subsection under ``RNA`` and the item
  ``SASHIMI.GENE_BACKGROUND_COLOR`` (default ``240,240,240``, format ``R,G,B``).
* ``SashimiPlot`` reads the preference in its constructor and applies it to the
  gene ``TrackComponent`` and the scroll-pane viewport background via
  ``applyGeneBackground()``; ``addWheelZoomListeners()`` installs the zoom
  listeners described above.
* ``GeneTrackMouseAdapter.getPopupMenu()`` adds the ``Set Background Color…``
  item after the display-mode items; it uses a colour chooser, writes the value
  into the preference, and repaints immediately.

.. note::

   These are changes to the Sashimi plot only. The general Ctrl + wheel zoom and
   zoom-to-cursor behaviour of the main data panels is described in
   :doc:`view_interactions`.
