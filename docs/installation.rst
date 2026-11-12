=============================
Installation and first launch
=============================

``myIGV`` (the application is named ``myigv`` in its window title and
``Application-Name``) is a fork of the Broad Institute Integrative Genomics
Viewer, built from the IGV 2.19.8 source. It adds several features on top of
upstream IGV 2.19.8 — most notably a bundled offline annotation pack — while
keeping the rest of the IGV experience unchanged. This guide documents only
those additions.

The quickest way to get started is to use a prebuilt installer. Building from
source is also supported and is described below.

Installers
----------

Prebuilt installers are published on the project's **GitHub Releases** page:

* a Windows ``.exe`` installer;
* a macOS ``.dmg`` disk image.

Each installer ships with a **bundled Java runtime (JRE)** and the **offline
annotation pack**, so you do not need to install Java separately and the
application works without a network connection for genome and annotation
resources (see :doc:`offline_resources` for exactly what is and is not bundled).

Both installers are produced automatically by the GitHub Actions workflow
``.github/workflows/build-installers.yml``. The workflow runs on pushes to
``main``, on ``v*`` tags, and on manual ``workflow_dispatch`` triggers, and it
builds on ``windows-latest`` and ``macos-latest`` respectively. It:

#. runs ``./gradlew createDist`` to produce ``build/myigv-dist`` (containing
   ``lib/`` and ``pack/``);
#. runs ``jlink --add-modules ALL-MODULE-PATH`` to build a runtime image — only
   the JDK's own modules are linked, because handing IGV's automatic modules to
   ``jlink`` (for example ``batik.xml``) fails. ``jpackage --runtime-image`` then
   reuses this image directly;
#. runs ``jpackage`` to produce the installer and passes ``--app-content`` so
   the ``pack/`` directory is included, guaranteeing offline operation out of
   the box. On Windows the ``.exe`` installer needs WiX (the workflow installs
   it with ``choco install wixtoolset``); on macOS the result is a ``.dmg``.

The installers are uploaded as workflow artifacts, and on a ``v*`` tag they are
also attached automatically to the corresponding GitHub Release.

.. note::

   Upstream IGV 2.19.8 downloads annotation resources on demand at startup.
   ``myIGV`` instead pre-packages them into the installer — see
   :doc:`offline_resources`.

.. warning::

   The macOS ``.dmg`` is **not code-signed or notarised**. The first time you
   open it, Gatekeeper may refuse to launch it. To get around this, use
   **right-click → Open** on the application (rather than a plain double-click),
   then confirm in the dialog that appears.

Building from source
--------------------

To build and run from source you need **JDK 21**. From the repository root,
create the distribution and then launch the generated start script:

.. code-block:: bash

   ./gradlew createDist            # on Windows use: gradlew.bat createDist
   build/myigv-dist/myigv.bat      # Windows
   build/myigv-dist/myigv.sh       # Linux / macOS

The ``createDist`` task assembles ``build/myigv-dist`` and copies the offline
``pack/`` directory into ``build/myigv-dist/pack`` (see
:doc:`offline_resources`).

The repository also ships a convenience launcher, ``启动myigv.bat``, at its
root for Windows users who prefer a one-click start.

.. tip::

   ``createDist`` is the same task the installer workflow uses, so a locally
   built distribution has the same layout and the same bundled resources as an
   official installer.

Troubleshooting from a terminal
-------------------------------

Startup problems (missing resources, Java errors, packaging issues) are easiest
to diagnose when you launch directly from a terminal:

* on Windows run ``build/myigv-dist/myigv.bat`` from a Command Prompt or Git
  Bash, or use ``启动myigv-控制台.bat``;
* on Linux / macOS run ``build/myigv-dist/myigv.sh``.

Running the start script from a terminal prints errors and the application log
directly to the terminal window instead of discarding them, which makes a
failed launch immediately visible.

.. note::

   ``myIGV`` keeps its user data directory isolated from a system-wide IGV
   installation via ``-Dmyigv.dir``; it does **not** share ``~/.igv``. This is
   covered in detail in :doc:`offline_resources`.
