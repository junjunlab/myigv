Data Tracks column and layer rows
=================================

The left-hand column of the Circular View is titled ``Data Tracks``.  It is the
control surface for everything drawn on the ring: normal IGV tracks *and* the
circular-only layers created by :doc:`circular_import`.

.. important::

   **Row order is radial order.**  The top row is the outermost ring band and
   the bottom row is the innermost.  Dragging a row changes the figure
   immediately; there is no separate "apply" step.

Two stacked sections
--------------------

The column is **one column with two sections**:

.. list-table::
   :header-rows: 1
   :widths: 30 70

   * - Section
     - Contents
   * - ``Data Tracks``
     - Normal IGV tracks and every circular-only layer whose mode is *inside*.
   * - ``Outside the ideogram``
     - Only layers whose mode is *outside*.  **Hidden entirely while it is
       empty**, so it takes no space until you set a layer outside.

Every circular-only layer (heatmap, labels, points, lines, histogram,
rectangles) has an ``… outside the ideogram`` checkbox in its right-click menu.
Ticking it moves that row from ``Data Tracks`` down to ``Outside the ideogram``
and draws it beyond the chromosome band; unticking moves it back.  The outside
rows stack in their own order — first row nearest the chromosome, each next row
one step further out — whatever the layer kind, so heatmaps, labels and value
tracks can all be mixed in the outside stack.

.. list-table::
   :header-rows: 1
   :widths: 24 40 36

   * - Row kind
     - Label text
     - Where it is drawn
   * - Links layer
     - ``<file>   (links N)``
     - Ribbons/chords in the empty centre of the ring.
   * - Heatmap layer
     - ``<file>   (heatmap R x S)   · inside|outside``
     - A concentric band at its row position, or beyond the ideogram.
   * - Labels layer
     - ``<file>   (labels N)   · inside|outside``
     - A label ring at its row position, or beyond the ideogram.
   * - Points / Lines / Histogram / Rectangles
     - ``<file>   (points|lines|hist|rect N)   · inside|outside``
     - A data band at its row position, or one of the outside bands.
   * - Normal IGV track
     - ``<track display name>``
     - A concentric data band.

A hidden row gets a ``   (hidden)`` suffix.  ``refreshDataColumn()`` preserves
the dragged order in **both** sections, appends newly imported layers at the
**bottom** of the main section (so the first import stays on top), and
recomputes each layer's ``rowIndex`` / ``bandIndex`` (inside) or
``outsideIndex`` (outside) from the row list.

.. note::

   **Heatmap and labels bands are interleaved by row order.**  A labels row
   above a heatmap row really draws outside that heatmap band, and vice versa —
   the two kinds are not drawn in separate passes.  The same holds for the
   points / lines / histogram / rectangles bands.

.. note::

   A layer set to ``outside`` is drawn beyond the ideogram regardless of its
   row in ``Data Tracks`` (it has moved to the other section), so moving its
   row inside that section only reorders it among the other outside layers.

Dragging rows
-------------

Each section has its own ``TrackReorderHandler``.  On drop, ``moveDataRow``
reorders that section's row list, ``refreshDataColumn()`` re-derives the band
order, and the ring repaints.  Dragging inside ``Data Tracks`` changes the inner
band order; dragging inside ``Outside the ideogram`` changes the outward order.
To move a layer between the two sections, use its ``… outside the ideogram``
checkbox.  The horizontal view is unaffected — this reorders only the Circular
View composition.

.. figure:: images/polish_heat_first.png
   :alt: Circular ring with heatmap band placed first in the Data Tracks order
   :width: 65%

   Heatmap row first: the equal-width heatmap band sits at the outermost
   position of the band stack.  The identical render is reused for
   ``polish_legend_TOP_RIGHT.png`` in :doc:`circular_heatmap`.

.. figure:: images/polish_heat_second.png
   :alt: Same ring after moving the heatmap row one position down
   :width: 65%

   After dragging the heatmap row down, its band moves inward relative to the
   other data bands.  No parameters were changed — only the row order.

Right-click: layer rows
-----------------------

Right-clicking a **layer** row opens ``showLayerPopup``, which contains three
blocks:

#. A visibility checkbox, whose label depends on the layer kind:

   .. list-table::
      :header-rows: 1
      :widths: 34 66

      * - Layer kind
        - Visibility item
      * - Heatmap
        - ``Show heatmap``
      * - Labels
        - ``Show labels``
      * - Points / Lines / Histogram / Rectangles
        - ``Show points`` / ``Show lines`` / ``Show histogram`` /
          ``Show rectangles``
      * - Links
        - ``Show this links layer``

#. All parameters for that layer kind, including the ``… outside the ideogram``
   checkbox that moves the row between the two sections:

   * Links → :doc:`circular_links`
   * Heatmap → :doc:`circular_heatmap`
   * Labels → :doc:`circular_labels`
   * Points / Lines / Histogram / Rectangles → :doc:`circular_import`
     (colour, point size / line width, the Rectangles ``Colours`` fill+border
     submenu and ``Expand overlapping rectangles``, band height)

#. A removal item:

   .. list-table::
      :header-rows: 1
      :widths: 34 66

      * - Layer kind
        - Removal item
      * - Heatmap
        - ``Remove heatmap from circular view…``
      * - Labels
        - ``Remove labels from circular view…``
      * - Points / Lines / Histogram / Rectangles
        - ``Remove points|lines|histogram|rectangles from circular view…``
      * - Links
        - ``Remove links from circular view…``

Removal calls ``removeLayer(layer)``, refreshes the column and the ring, and
leaves no ghost row.  There is no ``(hidden)`` residue after a removal.

.. figure:: images/layers_ring.png
   :alt: Circular ring showing several circular-only layers together
   :width: 65%

   Links, heatmap and labels layers can all be active in the same figure; each
   has its own row and its own context menu.

Right-click: normal track rows
------------------------------

Right-clicking a **track** row shows the usual IGV track options plus
ring-specific ones:

* ``Track height… (<n> px)`` — band height; supports multi-selection.
* ``Delete track`` — **really deletes** the track (via ``IGV.deleteTracks``).
  ``forgetRingTrack`` first clears the ring-track reference, the hidden marker,
  the band configuration and all feature/data/adaptive/Hi-C tile caches.  Older
  builds only hid the track and left a ``(hidden)`` row behind; that behaviour
  is gone.
* ``Expand`` / collapse, ``Stack outer``, ``Graph type``, ``Y-axis range``,
  ``Y-axis scale (multi-sample)`` and ``Hi-C ring height… (<n> px)`` for Hi-C
  tracks.
* A ``Links`` submenu with the same ribbon styling items documented in
  :doc:`circular_links`, for interaction tracks loaded through
  ``File → Load from File...``.

.. note::

   A BED track bound to a Hi-C TAD/domain overlay is set invisible by
   ``HiCDomainOverlay.acquire()``.  ``circularColumnTracks()`` and
   ``drawableAsBand()`` both filter on ``isVisible()``, so that BED no longer
   appears as a row or as a separate band on the ring.  See :doc:`circular_hic`.

Typical workflow
----------------

#. Load a genome and the tracks you want (they become band rows).
#. ``Import ▾`` → any of ``Links``, ``Heatmap``, ``Labels``, ``Points``,
   ``Lines``, ``Histogram``, ``Rectangles`` (Ctrl/Shift-click several files at
   once to import many layers).
#. Drag rows until the radial stacking is right; use each layer's ``… outside
   the ideogram`` checkbox to move it to the outside stack.
#. Right-click each layer row to set its parameters.
#. ``Export ▾`` to write the figure (see :doc:`circular_export`).

.. tip::

   To delete several layers, Ctrl/Shift-click the rows in either section, then
   right-click one of the selected rows → ``Delete selected (N)``.  Circular
   layers are removed from the ring and normal tracks are unloaded.

.. tip::

   Because imported layers are not saved in sessions, keep the row order and
   parameter values you used in a short note alongside the exported figure if
   you need to rebuild it later.
