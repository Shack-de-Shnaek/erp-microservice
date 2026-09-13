# syntax=docker/dockerfile:1
FROM python:3.14-slim
WORKDIR /app

# Dependencies first, so editing server.py does not reinstall them.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py test_client.py ./

# In a container there is no client to launch this over a pipe, so the only useful transport is
# the one that listens. 0.0.0.0 rather than the 127.0.0.1 default: bound to loopback it would be
# reachable only from inside this container, which is nothing.
ENV MCP_TRANSPORT=streamable-http \
    MCP_HOST=0.0.0.0 \
    MCP_PORT=8765 \
    PYTHONUNBUFFERED=1

EXPOSE 8765
ENTRYPOINT ["python", "server.py"]
