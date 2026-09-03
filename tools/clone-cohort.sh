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
           Delgan/loguru kennethreitz/records benoitc/gunicorn
           django/django aio-libs/aiohttp encode/starlette encode/uvicorn
           psycopg/psycopg redis/redis-py paramiko/paramiko pypa/pip
           pypa/setuptools python-poetry/poetry tqdm/tqdm sympy/sympy
           marshmallow-code/marshmallow more-itertools/more-itertools
           pytoolz/toolz agronholm/anyio pytransitions/transitions
           python-pillow/Pillow streamlit/streamlit dbt-labs/dbt-core" ;;
  java)
    # square/moshi and square/okhttp were dropped: both are Kotlin now, so they measured
    # 2 and 129 Java lines and the LOC floor discarded them without saying so.
    REPOS="google/guava google/gson square/retrofit
           apache/commons-lang apache/commons-io junit-team/junit4
           mockito/mockito FasterXML/jackson-databind netty/netty
           ReactiveX/RxJava bumptech/glide zxing/zxing dromara/hutool
           apache/dubbo eclipse-vertx/vert.x apache/commons-collections
           google/auto apache/maven
           apache/commons-codec apache/commons-text apache/commons-cli
           apache/commons-compress apache/commons-math jhy/jsoup
           google/error-prone google/dagger ben-manes/caffeine
           assertj/assertj OpenFeign/feign LMAX-Exchange/disruptor
           javaparser/javaparser alibaba/fastjson2 h2database/h2database
           projectlombok/lombok apache/pdfbox xerial/sqlite-jdbc
           awaitility/awaitility spring-projects/spring-retry
           apache/commons-csv apache/commons-pool jdbi/jdbi" ;;
  js)
    REPOS="expressjs/express axios/axios lodash/lodash chalk/chalk
           sindresorhus/got koajs/koa fastify/fastify socketio/socket.io
           date-fns/date-fns colinhacks/zod reduxjs/redux prettier/prettier
           eslint/eslint vitejs/vite vuejs/core immutable-js/immutable
           TanStack/query remix-run/react-router nodejs/undici mrdoob/three.js
           webpack/webpack babel/babel jestjs/jest rollup/rollup
           sveltejs/svelte preactjs/preact jquery/jquery dayjs/dayjs
           moment/moment nodemailer/nodemailer validatorjs/validator.js
           gulpjs/gulp chartjs/Chart.js d3/d3 pmndrs/zustand
           sequelize/sequelize typeorm/typeorm trpc/trpc
           apollographql/apollo-client pixijs/pixijs" ;;
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
