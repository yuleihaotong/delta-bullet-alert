FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV PYTHONUNBUFFERED=1
EXPOSE 8710
VOLUME ["/data"]

# 容器里必须监听 0.0.0.0；外网暴露时务必设置 WATCHER_TOKEN
CMD ["python", "app.py", "--host", "0.0.0.0", "--port", "8710", "--db", "/data/watcher.db"]
