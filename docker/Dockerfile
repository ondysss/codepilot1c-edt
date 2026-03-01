# syntax=docker/dockerfile:1
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# --- Системные зависимости ---
# Xvfb нужен только для build-time p2 director (устанавливает плагин через Eclipse GTK)
RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb \
    libgtk-3-0 \
    libwebkit2gtk-4.1-0 \
    libxss1 \
    libnss3 \
    libsecret-1-0 \
    libglib2.0-0 \
    libx11-6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# --- Установка 1C:EDT через 1ce-installer-cli ---
COPY edt.tar /tmp/edt.tar

RUN mkdir -p /tmp/edt-distr && \
    tar -xf /tmp/edt.tar -C /tmp/edt-distr && \
    rm /tmp/edt.tar && \
    /tmp/edt-distr/1ce-installer-cli \
        --javahome /tmp/edt-distr/axiom-jdk-full-17.0.16+12-linux-x86_64/data \
        --verbose info \
        install \
        --source /tmp/edt-distr \
        --products-home /opt/1c \
        --ignore-signature-warnings \
        --ignore-hardware-checks && \
    rm -rf /tmp/edt-distr && \
    EDT_DIR=$(find /opt/1c -name "1cedt" -executable -type f | head -1 | xargs -r dirname) && \
    echo "EDT dir: $EDT_DIR" && \
    ln -sf "$EDT_DIR" /opt/1cedt && \
    ls /opt/1cedt/

# --- Установка плагина через p2 director (нужен Xvfb + GTK) ---
COPY plugin.zip /tmp/plugin.zip

RUN Xvfb :99 -screen 0 1024x768x24 -nolisten tcp & sleep 3 && \
    DISPLAY=:99 /opt/1cedt/1cedt \
        -application org.eclipse.equinox.p2.director \
        -noSplash \
        -repository "jar:file:///tmp/plugin.zip!/" \
        -installIU "com.codepilot1c.feature.feature.group" \
        -destination /opt/1cedt && \
    rm /tmp/plugin.zip && \
    pkill Xvfb || true && \
    rm -f /tmp/.X99-lock /tmp/.X99-unix/X99 2>/dev/null || true

# --- CLI режим: com.codepilot1c.core должен стартовать автоматически ---
# Патчим bundles.info: autoStart false -> true для core-бандла
# Это позволяет VibeCorePlugin.start() вызваться без workbench (eclipse.ignoreApp=true)
RUN BUNDLES_INFO=/opt/1cedt/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info && \
    sed -i 's/^\(com\.codepilot1c\.core,[^,]*,[^,]*,4\),false$/\1,true/' "$BUNDLES_INFO" && \
    echo "Updated bundles.info:" && grep codepilot "$BUNDLES_INFO"

# --- MCP Host конфигурация через JVM system properties ---
# eclipse.ignoreApp + osgi.noShutdown = CLI headless режим без workbench и без Xvfb
RUN echo "-Dcodepilot.mcp.enabled=true" >> /opt/1cedt/1cedt.ini && \
    echo "-Dcodepilot.mcp.host.http.enabled=true" >> /opt/1cedt/1cedt.ini && \
    echo "-Dcodepilot.mcp.host.http.bindAddress=0.0.0.0" >> /opt/1cedt/1cedt.ini && \
    echo "-Dcodepilot.mcp.host.http.port=8765" >> /opt/1cedt/1cedt.ini && \
    echo "-Dcodepilot.mcp.host.auth.mode=BEARER_ONLY" >> /opt/1cedt/1cedt.ini && \
    echo "-Dcodepilot.mcp.host.policy.defaultMutationDecision=ALLOW" >> /opt/1cedt/1cedt.ini && \
    echo "-Dcodepilot.mcp.host.policy.exposedTools=*" >> /opt/1cedt/1cedt.ini && \
    echo "-Declipse.ignoreApp=true" >> /opt/1cedt/1cedt.ini && \
    echo "-Dosgi.noShutdown=true" >> /opt/1cedt/1cedt.ini

# --- Entrypoint ---
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8765
ENTRYPOINT ["docker-entrypoint.sh"]
