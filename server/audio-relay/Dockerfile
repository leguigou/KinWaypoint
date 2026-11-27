FROM python:3.12-alpine
RUN pip install --no-cache-dir websockets==13.1
WORKDIR /app
COPY relay.py .
USER nobody
EXPOSE 8765
HEALTHCHECK --interval=60s --timeout=5s CMD wget -q -O- http://127.0.0.1:8765/health || exit 1
CMD ["python", "-u", "relay.py"]
