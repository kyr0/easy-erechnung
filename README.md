# Install

## Setup
    
    > python install.py
    > pip show jep | grep "^Location:" | cut -d ':' -f 2 | cut -d ' ' -f 2
    > # e.g. /Users/admin/miniconda3/lib/python3.12/site-packages/
    > export PYTHONHOME=/Users/admin/miniconda3
    > python ocr.py verify.pdf verify.json

