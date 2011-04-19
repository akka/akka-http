# -*- coding: utf-8 -*-
#
# Akka documentation build configuration file.
#

import sys, os

# -- General configuration -----------------------------------------------------

extensions = ['sphinx.ext.todo']

templates_path = ['_templates']
source_suffix = '.rst'
master_doc = 'index'
exclude_patterns = ['_build', 'pending']

project = u'Akka'
copyright = u'2009-2011, Scalable Solutions AB'
version = '1.1'
release = '1.1'

pygments_style = 'simple'
highlight_language = 'scala'
add_function_parentheses = False
show_authors = True

# -- Options for HTML output ---------------------------------------------------

html_theme = 'akka'
html_theme_options = {
    'full_logo': 'true'
    }
html_theme_path = ['themes']

html_title = 'Akka Documentation'
html_logo = '_static/logo.png'
#html_favicon = None

html_static_path = ['_static']

html_last_updated_fmt = '%b %d, %Y'
#html_sidebars = {}
#html_additional_pages = {}
html_domain_indices = False
html_use_index = False
html_show_sourcelink = False
html_show_sphinx = False
html_show_copyright = True
htmlhelp_basename = 'Akkadoc'

# -- Options for LaTeX output --------------------------------------------------

latex_paper_size = 'a4'
latex_font_size = '10pt'

latex_documents = [
  ('index', 'Akka.tex', u' Akka Documentation',
   u'Scalable Solutions AB', 'manual'),
]

latex_elements = {
    'classoptions': ',oneside,openany',
    'babel': '\\usepackage[english]{babel}',
    'preamble': '\\definecolor{VerbatimColor}{rgb}{0.935,0.935,0.935}'
    }

# latex_logo = '_static/akka.png'
