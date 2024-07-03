FROM pennsieve/postgres:16.3

USER root

RUN apk update && \
    apk add --no-cache python3 py3-pip

RUN python3 -m venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH"

RUN . /opt/venv/bin/activate && \
    pip install --upgrade pip && \
    pip install awscli==1.33.20 && \
    aws --version

USER postgres

COPY healthcheck.sh /healthcheck.sh
COPY load-dump.sh /docker-entrypoint-initdb.d
