#!/usr/bin/env bash
# Shallow-clones a fixed cohort of public repositories so an absolute duplication threshold can
# be derived from a real distribution. HEAD only - the ratio does not need history.
#
#   ./tools/clone-cohort.sh /tmp/cohort-py py
set -uo pipefail
DEST="${1:?destination directory}"
LANG_KEY="${2:-py}"
mkdir -p "$DEST"

case "$LANG_KEY" in
  py)
    REPOS="psf/requests pallets/flask encode/httpx pallets/click Textualize/rich
           pydantic/pydantic tiangolo/fastapi psf/black pytest-dev/pytest
           sqlalchemy/sqlalchemy scrapy/scrapy pallets/jinja pallets/werkzeug
           tornadoweb/tornado celery/celery arrow-py/arrow python-attrs/attrs
           Delgan/loguru kennethreitz/records benoitc/gunicorn" ;;
  java)
    REPOS="google/guava google/gson square/okhttp square/retrofit
           apache/commons-lang apache/commons-io junit-team/junit4
           mockito/mockito FasterXML/jackson-databind netty/netty
           ReactiveX/RxJava bumptech/glide zxing/zxing dromara/hutool
           apache/dubbo eclipse-vertx/vert.x apache/commons-collections
           google/auto square/moshi apache/maven" ;;
  js)
    REPOS="expressjs/express axios/axios lodash/lodash chalk/chalk
           sindresorhus/got koajs/koa fastify/fastify socketio/socket.io
           date-fns/date-fns colinhacks/zod reduxjs/redux prettier/prettier
           eslint/eslint vitejs/vite vuejs/core immutable-js/immutable
           TanStack/query remix-run/react-router nodejs/undici mrdoob/three.js" ;;
  *)
    echo "unknown language key: $LANG_KEY (expected py, java or js)" >&2; exit 1 ;;
esac

for slug in $REPOS; do
  name=$(echo "$slug" | tr '/' '_')
  if [ -d "$DEST/$name/.git" ]; then echo "have $name"; continue; fi
  echo "clone $slug"
  git clone --depth 1 --single-branch --quiet "https://github.com/$slug.git" "$DEST/$name" \
    || echo "  FAILED $slug"
done
du -sh "$DEST"
