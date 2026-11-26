FROM python:3.12-alpine

WORKDIR /app
COPY app.py .

ENV KW_DATA_DIR=/data \
    KW_PORT=8080 \
    TZ=Europe/Paris \
    PYTHONUNBUFFERED=1

# tzdata : sans lui, TZ=Europe/Paris est ignoré et les dates restent en UTC.
RUN apk add --no-cache tzdata \
    && mkdir -p /data \
    && adduser -D -u 10001 kw \
    && chown -R kw /data /app
USER kw

VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1

CMD ["python3", "app.py"]
