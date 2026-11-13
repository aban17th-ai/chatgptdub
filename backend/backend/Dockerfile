
FROM python:3.11-slim

ARG INSTALL_DIARIZATION=false
ARG INSTALL_DEMUCS=false

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt requirements-diarization.txt requirements-dsp.txt ./

RUN pip install --no-cache-dir -r requirements.txt \
    && if [ "$INSTALL_DIARIZATION" = "true" ]; then \
       pip install --no-cache-dir -r requirements-diarization.txt; \
       fi \
    && if [ "$INSTALL_DEMUCS" = "true" ]; then \
       pip install --no-cache-dir -r requirements-dsp.txt; \
       fi

COPY . .

ENV DATA_DIR=/data

RUN mkdir -p /data/uploads /data/outputs

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
