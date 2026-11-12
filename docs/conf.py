# Sphinx configuration for the myIGV user guide.
#
# This project documents ONLY the features that myIGV adds on top of upstream
# IGV 2.19.8.  Stock IGV behaviour is described at https://igv.org/doc/desktop/
# and is intentionally not duplicated here.

import datetime

# -- Project information -----------------------------------------------------

project = "myIGV"
author = "myIGV developers"
copyright = "%s, %s" % (datetime.date.today().year, author)

# The short X.Y version and the full version, including alpha/beta/rc tags.
version = "1.0.2"
release = "1.0.2"

# -- General configuration ---------------------------------------------------

extensions = []

templates_path = ["_templates"]

# docs/ also contains upstream IGV's static web test pages (docs/web/ and
# docs/index.md).  They are not part of this guide, so keep Sphinx away from
# them; index.rst is the master document of the myIGV guide.
source_suffix = {
    ".rst": "restructuredtext",
}
exclude_patterns = [
    "_build",
    "Thumbs.db",
    ".DS_Store",
    "web",
    "index.md",
    "README*",
]
master_doc = "index"
language = "en"
pygments_style = "sphinx"
todo_include_todos = False

# -- Options for HTML output -------------------------------------------------

html_theme = "sphinx_rtd_theme"
html_title = "myIGV User Guide"
html_static_path = ["_static"]
html_theme_options = {
    "collapse_navigation": False,
    "navigation_depth": 3,
    "sticky_navigation": True,
    "style_external_links": True,
}
htmlhelp_basename = "myIGVdoc"

# -- Options for LaTeX / PDF output ------------------------------------------

latex_elements = {
    "papersize": "a4paper",
    "pointsize": "10pt",
}
latex_documents = [
    (master_doc, "myIGV.tex", "myIGV User Guide", author, "manual"),
]

# -- Options for EPUB output -------------------------------------------------

epub_title = project
epub_author = author
epub_publisher = author
epub_copyright = copyright
