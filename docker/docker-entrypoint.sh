#!/usr/bin/env bash
set -e

# Применяем bearerToken из env если задан
if [ -n "${MCP_BEARER_TOKEN}" ]; then
    echo "-Dcodepilot.mcp.host.http.bearerToken=${MCP_BEARER_TOKEN}" >> /opt/1cedt/1cedt.ini
fi

# Монтируем проекты в workspace
# PROJECT_PATH - путь к одному проекту или список через ':' (как CLASSPATH)
# Пример: -e PROJECT_PATH=/projects/MyProject
#          -e PROJECT_PATH=/projects/ProjectA:/projects/ProjectB
mkdir -p /workspace

if [ -n "${PROJECT_PATH}" ]; then
    echo "[entrypoint] Linking projects from PROJECT_PATH..."
    IFS=':' read -ra PATHS <<< "${PROJECT_PATH}"
    for p in "${PATHS[@]}"; do
        if [ -d "$p" ]; then
            name=$(basename "$p")
            ln -sfn "$p" "/workspace/$name"
            echo "[entrypoint]   -> /workspace/$name -> $p"
        else
            echo "[entrypoint]   WARNING: $p is not a directory, skipping"
        fi
    done
fi

echo "[entrypoint] Starting 1C:EDT CLI mode (headless) with MCP Host on port ${MCP_PORT:-8765}..."
exec /opt/1cedt/1cedt -nosplash -data /workspace "$@"
