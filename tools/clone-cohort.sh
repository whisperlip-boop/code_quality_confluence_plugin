#!/usr/bin/env bash
# Shallow-clones a fixed cohort of public repositories so an absolute duplication threshold can
# be derived from a real distribution. HEAD only - the ratio does not need history.
#
#   ./tools/clone-cohort.sh /tmp/cohort-py py
set -uo pipefail
DEST="${1:?destination directory}"
LANG_KEY="${2:-py}"
mkdir -p "$DEST"

if [ "$LANG_KEY" = "py" ]; then
  REPOS="psf/requests pallets/flask encode/httpx pallets/click Textualize/rich
         pydantic/pydantic tiangolo/fastapi psf/black pytest-dev/pytest
         sqlalchemy/sqlalchemy scrapy/scrapy pallets/jinja pallets/werkzeug
         tornadoweb/tornado celery/celery arrow-py/arrow python-attrs/attrs
         Delgan/loguru kennethreitz/records benoitc/gunicorn"
else
  REPOS=""
fi

for slug in $REPOS; do
  name=$(echo "$slug" | tr '/' '_')
  if [ -d "$DEST/$name/.git" ]; then echo "have $name"; continue; fi
  echo "clone $slug"
  git clone --depth 1 --single-branch --quiet "https://github.com/$slug.git" "$DEST/$name" \
    || echo "  FAILED $slug"
done
du -sh "$DEST"
