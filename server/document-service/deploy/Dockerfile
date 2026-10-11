# BASE_IMAGE is resolved to a digest and recorded before building.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
RUN apt-get update && apt-get install -y --no-install-recommends libgl1=1.6.0-1 libglib2.0-0=2.74.6-2+deb12u9 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements-worker.lock /app/
RUN pip install --no-cache-dir --require-hashes -r requirements-worker.lock
COPY worker /app/worker
COPY deploy/mineru.yaml /app/mineru.yaml
ENV MINERU_CONFIG=/app/mineru.yaml MINERU_HOME=/tmp/mineru \
    HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 HF_HUB_DISABLE_TELEMETRY=1 \
    MINERU_TABLE_DEVICE=cpu OMP_NUM_THREADS=4 OPENBLAS_NUM_THREADS=4 \
    MKL_NUM_THREADS=4 PYTHONDONTWRITEBYTECODE=1
USER 65534:65534
ENTRYPOINT ["python", "-m", "worker.run"]
