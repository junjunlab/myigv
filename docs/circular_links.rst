Links layers: ribbons and arrows
================================

A links layer is created by ``Import ▾ → Links  (BEDPE / Circos links)…``
(see :doc:`circular_import`).  Right-click its row in the Data Tracks column to
open its parameter menu.  The same styling menu is also reachable from the
``Links`` submenu of a normal interaction track loaded through
``File → Load from File...``.

.. note::

   These are *drawing* parameters for the ring.  They are not the arc-shape /
   arc-height settings of a linear BEDPE interaction track — those live in
   :doc:`desktop_extras`.

Visibility and geometry
-----------------------

.. list-table::
   :header-rows: 1
   :widths: 34 14 52

   * - Menu item
     - Default
     - What it controls
   * - ``Show links``
     - on
     - Master switch for all link layers in the figure.
   * - ``Draw inside data tracks``
     - on
     - Anchor links inside the stacked ring bands instead of on top of them.
       Turn it off when the bands are crowded and you want the links to pass in
       front of them.
   * - ``Ribbon (filled bands)``
     - on
     - Draw each link as a filled band (Circos ``ribbon=yes``) rather than a
       stroked curve.  Even when off, the other ribbon parameters below still
       govern the chord geometry.
   * - ``Twist ribbons``
     - on
     - Swap which end of the second interval the two ribbon edges connect to
       (Circos ``twist``).  In myIGV twist is implemented as a **mild waist
       narrowing** (factor ``k = 0.22`` at the midpoint), not a full pinch: the
       band keeps a visible width along its entire length.  This is the default
       and is the main visual difference from a naive centreline ribbon whose
       width collapses to zero at ``t = 0.5``.
   * - ``Colour by chromosome``
     - off
     - Colour each link by its source chromosome instead of using one link
       colour.
   * - ``Colour…``
     - ``RGB(40, 90, 170)``
     - Single link colour when ``Colour by chromosome`` is off.
   * - ``Thickness… (<n> px)``
     - ``1.2 px``
     - Stroke width of non-ribbon links.  Values below ``0.1`` are clamped.

.. figure:: images/circular_links_ribbon.png
   :alt: Dense blue filled ribbon links inside a whole-genome circular ring
   :width: 65%

   ``Ribbon (filled bands)`` on: each link is a filled two-edge Bezier band.
   ``Twist ribbons`` (default on) narrows the waist slightly but never closes
   it.

.. figure:: images/circular_links_straight.png
   :alt: Straight chord links across a circular ring
   :width: 65%

   With ``Bezier radius (0-1)…`` set to ``0`` the same links become straight
   chords — the Circos ``bezier_radius=0`` behaviour.

Ribbon body
-----------

The ribbon is a polygon bounded by two quadratic Bezier edges, following
``circos.link`` in the circlize R package.  A whole-genome BEDPE anchor is often
only a few kilobases, which is sub-pixel on the ring, so a minimum angular
width is applied at each end.

.. list-table::
   :header-rows: 1
   :widths: 38 14 48

   * - Menu item
     - Default
     - What it controls
   * - ``End radius (0-1)…``
     - ``0.95``
     - Radial position of the link ends as a fraction of the ideogram inner
       radius.  Range ``0.05 – 1.0``.  Lower it to keep links clear of data
       rings.
   * - ``Bezier radius (0-1)…``
     - ``0.25``
     - Radial position of the Bezier control point (Circos
       ``bezier_radius``).  ``0`` draws a straight chord; ``1`` pushes the
       control point to the centre.  Range ``0 – 1``.
   * - ``Crest (0-1)…``
     - ``0.5``
     - Adds control points that make links meet the ideogram perpendicularly
       (Circos ``crest``).  ``0`` disables.  Range ``0 – 1``.
   * - ``Min ribbon width (deg)…``
     - ``1.2°``
     - Minimum angular width of a ribbon end.  Without it, links with tiny
       anchors would be invisible.  Minimum ``0``.
   * - ``Ribbon transparency (0 = solid)…``
     - ``0``
     - ``0`` is fully opaque; ``255`` is invisible.  Internally stored as an
       alpha of ``255 - value``.  Raise it to let overlapping ribbons show
       through.
   * - ``Outline ribbons``
     - on
     - Strokes the ribbon outline (Circos ``stroke_*``), which keeps a thin
       middle section readable.  Stroke thickness ``0.6 px``.
   * - ``Max links…``
     - ``5000``
     - Cap on links drawn per frame (Circos ``record_limit``).  Minimum ``1``.

An internal ``linkBezierPurity`` of ``0.75`` blends the control point between
the purely radial position and the Cartesian midpoint; it has no menu item.

.. figure:: images/ribbon_single.png
   :alt: One ribbon link showing its two curved edges and constant waist width
   :width: 55%

   A single ribbon.  Both edges remain separate at the midpoint — the band
   never degenerates into a needle.

.. figure:: images/circular_links_ribbon_mixed.png
   :alt: Mixed point-anchor and interval-anchor ribbons in one ring
   :width: 65%

   Mixed links where some ends are single points and others are intervals.
   ``Min ribbon width (deg)…`` keeps the point ends visible.

Arrows
------

The ``Arrows`` submenu adds directional heads.  This is a myIGV addition and is
**off by default**.

.. list-table::
   :header-rows: 1
   :widths: 34 16 50

   * - ``Arrows`` option
     - Value
     - Effect
   * - ``None``
     - ``0`` (default)
     - No arrow heads.
   * - ``Start (first interval)``
     - ``1``
     - An arrow head at the first BEDPE interval.
   * - ``End (second interval)``
     - ``2``
     - An arrow head at the second BEDPE interval.
   * - ``Both ends``
     - ``3``
     - Arrow heads at both intervals.

.. important::

   **The arrow shape depends on the interval width.**  An end is treated as a
   *point* when ``end - start <= 1`` bp; otherwise it is an *interval*.

   * A **point** end gets a thin arrow head — the circlize-style narrow
     triangle.
   * An **interval** end gets a **band arrow**: the ribbon polygon's edges stop
     at ``rou - arr.length`` and then close onto the arc midpoint, so the band
     body itself tapers into the head (the ``arr.type = "big.arrow"`` /
     ``arc.midpoint`` construction from ``R/link.R``).  It is *not* a small
     triangle glued to the outside of a ribbon.

.. list-table::
   :header-rows: 1
   :widths: 36 14 50

   * - Menu item
     - Default
     - What it controls
   * - ``Arrow length… (<n> px)``
     - ``9 px``
     - Length of the head in pixels; clamped to ``3 – 80``.
   * - ``Arrow width… (<n>x band)``
     - ``1.7x``
     - Base width of the head as a multiple of the band width; clamped to
       ``1.0 – 4.0``.

.. figure:: images/arrow_point_both.png
   :alt: A point-anchored link with thin arrow heads at both ends
   :width: 55%

   ``Arrows → Both ends`` with **point** anchors (``end - start <= 1``): thin
   arrow heads at both ends.

.. figure:: images/arrow_band_both.png
   :alt: An interval-anchored link whose ribbon tapers into band arrow heads
   :width: 55%

   ``Arrows → Both ends`` with **interval** anchors: the ribbon body itself
   closes into the band arrow heads.

Other appearance
----------------

.. list-table::
   :header-rows: 1
   :widths: 38 14 48

   * - Menu item
     - Default
     - What it controls
   * - ``Transparency (0 = solid)…``
     - ``0``
     - Transparency of stroked (non-ribbon) links.  ``0`` solid, ``255``
       invisible.  Stored as ``255 - value``.
   * - ``Show this links layer``
     - on
     - Visibility checkbox at the top of this layer's context menu, from
       :doc:`circular_data_tracks`.
   * - ``Remove links from circular view…``
     - —
     - Deletes this layer entirely.

.. figure:: images/circular_links_inside_tracks.png
   :alt: Links anchored inside the stacked data bands of a circular figure
   :width: 65%

   ``Draw inside data tracks`` on: link ends sit inside the band stack rather
   than on top of it.

.. tip::

   For a dense whole-genome figure, start with ``Ribbon transparency`` around
   ``60 – 100`` and ``Max links…`` around ``2000``.  Both changes are instant;
   neither requires re-importing the file.
