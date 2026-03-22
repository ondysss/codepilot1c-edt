(function () {
  const state = {
    clientId: "",
    sessionId: "",
    controller: false,
    controllerClientId: "",
    lastSequence: 0,
    eventSource: null,
    timeline: [],
    toolEvents: [],
    pendingConfirmation: {},
    ideSnapshot: null,
    profiles: [],
    commands: [],
    notices: [],
  };

  const labels = {
    eventTypes: {
      session_reset: "Сброс сессии",
      lease_changed: "Смена управления",
      agent_start_requested: "Запрос на запуск агента",
      agent_input_requested: "Запрос на продолжение агента",
      agent_started: "Агент запущен",
      agent_step: "Шаг агента",
      tool_call: "Вызов инструмента",
      tool_result: "Результат инструмента",
      stream_chunk: "Фрагмент потока",
      stream_complete: "Поток завершен",
      confirmation_required: "Требуется подтверждение",
      confirmation_resolved: "Подтверждение обработано",
      remote_action_result: "Результат удаленного действия",
      agent_completed: "Агент завершил работу",
      agent_stop_requested: "Запрос на остановку агента",
      ide_open_file: "Открытие файла",
      ide_reveal_range: "Показ диапазона",
      ide_get_selection: "Получение выделения",
      ide_show_view: "Открытие представления",
      ide_activate_part: "Активация части IDE",
    },
    states: {
      IDLE: "Ожидание",
      RUNNING: "Выполняется",
      WAITING_TOOL: "Ожидает инструмент",
      WAITING_CONFIRMATION: "Ожидает подтверждение",
      COMPLETED: "Завершено",
      CANCELLED: "Остановлено",
      ERROR: "Ошибка",
    },
    modes: {
      claimed: "Захвачено",
      force_takeover: "Принудительный перехват",
      released: "Освобождено",
    },
    keys: {
      available: "Доступно",
      reason: "Причина",
      workspaceRoot: "Корень рабочей области",
      activePartId: "Идентификатор активной части",
      activePartTitle: "Заголовок активной части",
      filePath: "Путь к файлу",
      offset: "Смещение",
      length: "Длина",
      selectedText: "Выделенный текст",
      startLine: "Начальная строка",
      endLine: "Конечная строка",
      path: "Путь",
      severity: "Серьезность",
      line: "Строка",
      message: "Сообщение",
      id: "Идентификатор",
      name: "Название",
      description: "Описание",
      denied: "Запрещено",
      viewId: "Идентификатор представления",
      partId: "Идентификатор части",
      commandId: "Идентификатор команды",
      result: "Результат",
      controllerClientId: "Клиент управления",
      mode: "Режим",
      promptLength: "Длина запроса",
      profileId: "Идентификатор профиля",
      scope: "Область",
      confirmationId: "Идентификатор подтверждения",
      toolName: "Имя инструмента",
      arguments: "Аргументы",
      destructive: "Разрушающее действие",
      success: "Успех",
      executionTimeMs: "Время выполнения, мс",
      content: "Содержимое",
      finishReason: "Причина завершения",
      complete: "Завершено",
      state: "Состояние",
      steps: "Шаги",
      toolCalls: "Вызовы инструментов",
      finalResponse: "Итоговый ответ",
      errorMessage: "Сообщение об ошибке",
      startColumn: "Начальный столбец",
      endColumn: "Конечный столбец",
      replaceSelection: "Заменить выделение",
      ok: "Успешно",
      code: "Код",
      clientId: "Идентификатор клиента",
      controller: "Управление",
      sessionId: "Идентификатор сессии",
      readOnly: "Только чтение",
    },
    cards: {
      workbench: "Рабочая среда",
      editor: "Редактор",
      diagnostics: "Диагностика",
      openViews: "Открытые представления",
    },
    results: {
      workbenchCommand: "Команда рабочей среды",
      openFile: "Открытие файла",
      selection: "Выделение",
      revealRange: "Показ диапазона",
      editorMutation: "Изменение редактора",
    },
  };

  const eventTypes = [
    "session_reset",
    "lease_changed",
    "agent_start_requested",
    "agent_input_requested",
    "agent_started",
    "agent_step",
    "tool_call",
    "tool_result",
    "stream_chunk",
    "stream_complete",
    "confirmation_required",
    "confirmation_resolved",
    "remote_action_result",
    "agent_completed",
    "agent_stop_requested",
    "ide_open_file",
    "ide_reveal_range",
    "ide_get_selection",
    "ide_show_view",
    "ide_activate_part",
  ];

  const els = {
    app: document.getElementById("app"),
    loginCard: document.getElementById("login-card"),
    loginForm: document.getElementById("login-form"),
    tokenInput: document.getElementById("token-input"),
    loginError: document.getElementById("login-error"),
    noticeBar: document.getElementById("notice-bar"),
    refreshBootstrap: document.getElementById("refresh-bootstrap"),
    claimController: document.getElementById("claim-controller"),
    releaseController: document.getElementById("release-controller"),
    logout: document.getElementById("logout"),
    sessionBadges: document.getElementById("session-badges"),
    promptInput: document.getElementById("prompt-input"),
    profileSelect: document.getElementById("profile-select"),
    agentStart: document.getElementById("agent-start"),
    agentInput: document.getElementById("agent-input"),
    agentStop: document.getElementById("agent-stop"),
    timeline: document.getElementById("timeline"),
    pendingConfirmation: document.getElementById("pending-confirmation"),
    toolEvents: document.getElementById("tool-events"),
    ideSnapshot: document.getElementById("ide-snapshot"),
    snapshotRefresh: document.getElementById("snapshot-refresh"),
    commandForm: document.getElementById("command-form"),
    commandInput: document.getElementById("command-input"),
    commandList: document.getElementById("command-list"),
    commandParams: document.getElementById("command-params"),
    commandResults: document.getElementById("command-results"),
    editorOpenForm: document.getElementById("editor-open-form"),
    editorPath: document.getElementById("editor-path"),
    editorSelection: document.getElementById("editor-selection"),
    editorRevealForm: document.getElementById("editor-reveal-form"),
    revealStartLine: document.getElementById("reveal-start-line"),
    revealStartColumn: document.getElementById("reveal-start-column"),
    revealEndLine: document.getElementById("reveal-end-line"),
    revealEndColumn: document.getElementById("reveal-end-column"),
    editorText: document.getElementById("editor-text"),
    replaceSelection: document.getElementById("replace-selection"),
    insertAtCursor: document.getElementById("insert-at-cursor"),
    applyGeneratedCode: document.getElementById("apply-generated-code"),
    applyReplaceSelection: document.getElementById("apply-replace-selection"),
    editorResults: document.getElementById("editor-results"),
  };

  function init() {
    bindActions();
    refreshBootstrap();
  }

  function bindActions() {
    els.loginForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      els.loginError.textContent = "";
      const token = els.tokenInput.value.trim();
      if (!token) {
        els.loginError.textContent = "Нужно указать bearer-токен.";
        return;
      }
      const result = await api("/remote/api/auth/login", {
        method: "POST",
        body: { token },
      });
      if (!result.ok) {
        const code = result.payload && result.payload.code;
        els.loginError.textContent = code === "oauth_only"
          ? "Удаленный интерфейс недоступен в режиме OAUTH_ONLY. Включите режим резервного bearer-токена."
          : result.message;
        return;
      }
      els.tokenInput.value = "";
      notify(result.message, "ok");
      await refreshBootstrap();
    });

    els.refreshBootstrap.addEventListener("click", refreshBootstrap);
    els.snapshotRefresh.addEventListener("click", refreshSnapshot);

    els.claimController.addEventListener("click", async () => {
      const result = await api("/remote/api/controller/claim", {
        method: "POST",
        body: { force: false },
      });
      notify(result.message, result.ok ? "ok" : "error");
      if (!result.ok && result.payload && result.payload.controllerClientId) {
        notify("Текущий управляющий клиент: " + result.payload.controllerClientId, "error");
      }
      await refreshBootstrap();
    });

    els.releaseController.addEventListener("click", async () => {
      const result = await api("/remote/api/controller/release", { method: "POST" });
      notify(result.message, result.ok ? "ok" : "error");
      await refreshBootstrap();
    });

    els.logout.addEventListener("click", async () => {
      await api("/remote/api/auth/logout", { method: "POST" });
      closeEvents();
      resetView();
      notify("Удаленная сессия очищена.", "ok");
    });

    els.agentStart.addEventListener("click", () => submitAgent("/remote/api/agent/start"));
    els.agentInput.addEventListener("click", () => submitAgent("/remote/api/agent/input"));
    els.agentStop.addEventListener("click", async () => {
      const result = await api("/remote/api/agent/stop", { method: "POST" });
      notify(result.message, result.ok ? "ok" : "error");
    });

    els.commandForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const commandId = els.commandInput.value.trim();
      if (!commandId) {
        notify("Нужно указать идентификатор команды.", "error");
        return;
      }
      const parameters = parseJsonText(els.commandParams.value.trim());
      if (parameters === null) {
        notify("Параметры команды должны быть корректным JSON.", "error");
        return;
      }
      const result = await api("/remote/api/workbench/command", {
        method: "POST",
        body: {
          clientRequestId: cryptoRandom(),
          kind: "workbench_command",
          payload: { commandId, parameters },
        },
      });
      renderResult(els.commandResults, labels.results.workbenchCommand, result.payload || result);
      notify(result.message, result.ok ? "ok" : "error");
    });

    els.editorOpenForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const result = await api("/remote/api/ide/editor/open-file", {
        method: "POST",
        body: { path: els.editorPath.value.trim() },
      });
      renderResult(els.editorResults, labels.results.openFile, result.payload || result);
      notify(result.message, result.ok ? "ok" : "error");
    });

    els.editorSelection.addEventListener("click", async () => {
      const result = await api("/remote/api/ide/editor/get-selection", { method: "POST" });
      renderResult(els.editorResults, labels.results.selection, result.payload || result);
      notify(result.message, result.ok ? "ok" : "error");
    });

    els.editorRevealForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const result = await api("/remote/api/ide/editor/reveal-range", {
        method: "POST",
        body: {
          path: els.editorPath.value.trim(),
          startLine: numberValue(els.revealStartLine.value, 1),
          startColumn: numberValue(els.revealStartColumn.value, 1),
          endLine: numberValue(els.revealEndLine.value, 1),
          endColumn: numberValue(els.revealEndColumn.value, 1),
        },
      });
      renderResult(els.editorResults, labels.results.revealRange, result.payload || result);
      notify(result.message, result.ok ? "ok" : "error");
    });

    els.replaceSelection.addEventListener("click", () => submitEditorMutation("/remote/api/ide/editor/replace-selection", {
      text: els.editorText.value,
    }));
    els.insertAtCursor.addEventListener("click", () => submitEditorMutation("/remote/api/ide/editor/insert-at-cursor", {
      text: els.editorText.value,
    }));
    els.applyGeneratedCode.addEventListener("click", () => submitEditorMutation("/remote/api/ide/editor/apply-generated-code", {
      response: els.editorText.value,
      replaceSelection: els.applyReplaceSelection.checked,
    }));
  }

  async function submitAgent(path) {
    const prompt = els.promptInput.value.trim();
    if (!prompt) {
      notify("Нужно указать запрос.", "error");
      return;
    }
    const result = await api(path, {
      method: "POST",
      body: {
        prompt,
        profileId: els.profileSelect.value || "",
      },
    });
    notify(result.message, result.ok ? "ok" : "error");
  }

  async function submitEditorMutation(path, body) {
    const result = await api(path, { method: "POST", body });
    renderResult(els.editorResults, labels.results.editorMutation, result.payload || result);
    notify(result.message, result.ok ? "ok" : "error");
    renderPendingConfirmation();
  }

  async function refreshBootstrap() {
    const result = await api("/remote/api/bootstrap", { method: "GET", quietUnauthorized: true });
    if (!result.ok) {
      if (result.status === 401) {
        resetView();
        return;
      }
      notify(result.message, "error");
      return;
    }

    const payload = result.payload;
    state.clientId = payload.clientId || "";
    state.sessionId = payload.sessionId || "";
    state.controller = !!payload.controller;
    state.controllerClientId = payload.controllerClientId || "";
    state.pendingConfirmation = payload.pendingConfirmation || {};
    state.ideSnapshot = payload.ideSnapshot || null;
    state.profiles = payload.profiles || [];
    state.commands = (payload.ideSnapshot && payload.ideSnapshot.commands) || [];
    hydrateProfiles();
    renderShell(true);
    renderBadges(payload.agent || {});
    renderPendingConfirmation();
    renderIdeSnapshot();
    renderCommands();
    openEvents();
  }

  async function refreshSnapshot() {
    const result = await api("/remote/api/ide/snapshot", { method: "GET" });
    if (!result.ok) {
      notify(result.message, "error");
      return;
    }
    state.ideSnapshot = result.payload;
    state.commands = result.payload.commands || [];
    renderIdeSnapshot();
    renderCommands();
    notify("Снимок IDE обновлен.", "ok");
  }

  function openEvents() {
    closeEvents();
    const source = new EventSource("/remote/api/events?fromSequence=" + encodeURIComponent(state.lastSequence));
    state.eventSource = source;
    eventTypes.forEach((type) => {
      source.addEventListener(type, onRemoteEvent);
    });
    source.onerror = () => {
      notify("Поток событий прерван. Переподключение...", "error");
      closeEvents();
      window.setTimeout(openEvents, 1500);
    };
  }

  function closeEvents() {
    if (state.eventSource) {
      state.eventSource.close();
      state.eventSource = null;
    }
  }

  function onRemoteEvent(event) {
    const remoteEvent = JSON.parse(event.data);
    state.lastSequence = Math.max(state.lastSequence, remoteEvent.sequence || 0);
    state.timeline.unshift(remoteEvent);
    state.timeline = state.timeline.slice(0, 200);

    if (remoteEvent.type === "tool_call" || remoteEvent.type === "tool_result" || remoteEvent.type === "confirmation_required") {
      state.toolEvents.unshift(remoteEvent);
      state.toolEvents = state.toolEvents.slice(0, 80);
    }

    if (remoteEvent.type === "confirmation_required") {
      state.pendingConfirmation = remoteEvent.payload || {};
    } else if (remoteEvent.type === "confirmation_resolved" || remoteEvent.type === "remote_action_result") {
      state.pendingConfirmation = {};
    } else if (remoteEvent.type === "lease_changed") {
      state.controllerClientId = remoteEvent.payload.controllerClientId || "";
      state.controller = state.controllerClientId && state.controllerClientId === state.clientId;
    } else if (remoteEvent.type === "session_reset") {
      state.sessionId = remoteEvent.sessionId || state.sessionId;
    }

    renderTimeline();
    renderToolEvents();
    renderPendingConfirmation();
    renderBadges();
  }

  function hydrateProfiles() {
    els.profileSelect.innerHTML = "";
    state.profiles.forEach((profile) => {
      const option = document.createElement("option");
      option.value = profile.id || "";
      option.textContent = [profile.name || profile.id || "профиль", profile.readOnly ? "(только чтение)" : ""]
        .join(" ")
        .trim();
      els.profileSelect.appendChild(option);
    });
  }

  function renderShell(authenticated) {
    els.loginCard.classList.toggle("hidden", authenticated);
    els.app.classList.toggle("hidden", !authenticated);
  }

  function resetView() {
    closeEvents();
    state.clientId = "";
    state.sessionId = "";
    state.controller = false;
    state.controllerClientId = "";
    state.pendingConfirmation = {};
    state.ideSnapshot = null;
    state.profiles = [];
    state.commands = [];
    renderShell(false);
    renderBadges();
    renderPendingConfirmation();
    renderTimeline();
    renderToolEvents();
    renderIdeSnapshot();
    renderCommands();
  }

  function renderBadges(agent) {
    const effectiveAgent = agent || {};
    els.sessionBadges.innerHTML = "";
    addBadge(effectiveAgent.state ? "Состояние: " + localizeState(effectiveAgent.state) : "Не подключено", "ok");
    addBadge("Сессия: " + (state.sessionId || "н/д"));
    addBadge(state.controller ? "Управление" : "Только чтение", state.controller ? "ok" : "warn");
    addBadge("Захват: " + (state.controllerClientId || "свободно"));
  }

  function addBadge(label, extraClass) {
    const span = document.createElement("span");
    span.className = ["badge", extraClass || ""].join(" ").trim();
    span.textContent = label;
    els.sessionBadges.appendChild(span);
  }

  function renderTimeline() {
    if (!state.timeline.length) {
      els.timeline.innerHTML = '<div class="event empty">Событий пока нет.</div>';
      return;
    }
    els.timeline.innerHTML = "";
    state.timeline.forEach((item) => {
      const node = document.createElement("article");
      node.className = "event";
      node.innerHTML =
        "<header><span class=\"event-type\">" + escapeHtml(localizeEventType(item.type)) + "</span><span class=\"muted\">" +
        escapeHtml(formatTime(item.timestamp)) + " · #" + escapeHtml(String(item.sequence || "")) +
        "</span></header><pre>" + escapeHtml(prettyLocalized(item.payload || {})) + "</pre>";
      els.timeline.appendChild(node);
    });
  }

  function renderToolEvents() {
    if (!state.toolEvents.length) {
      els.toolEvents.innerHTML = '<div class="tool-event empty">Активность инструментов пока не зафиксирована.</div>';
      return;
    }
    els.toolEvents.innerHTML = "";
    state.toolEvents.forEach((item) => {
      const node = document.createElement("article");
      node.className = "tool-event";
      node.innerHTML =
        "<header><strong>" + escapeHtml(localizeEventType(item.type)) + "</strong><span class=\"muted\">" +
        escapeHtml(formatTime(item.timestamp)) + "</span></header><pre>" +
        escapeHtml(prettyLocalized(item.payload || {})) + "</pre>";
      els.toolEvents.appendChild(node);
    });
  }

  function renderPendingConfirmation() {
    const pending = state.pendingConfirmation || {};
    const confirmationId = pending.confirmationId || "";
    if (!confirmationId) {
      els.pendingConfirmation.className = "confirmation empty";
      els.pendingConfirmation.textContent = "Нет ожидающих подтверждений.";
      return;
    }

    els.pendingConfirmation.className = "confirmation";
    els.pendingConfirmation.innerHTML =
      "<strong>Ожидает подтверждения</strong><pre>" + escapeHtml(prettyLocalized(pending)) + "</pre>";

    const actions = document.createElement("div");
    actions.className = "confirmation-actions";

    const approve = document.createElement("button");
    approve.textContent = "Подтвердить";
    approve.addEventListener("click", () => resolveConfirmation(true, confirmationId));
    actions.appendChild(approve);

    const reject = document.createElement("button");
    reject.textContent = "Отклонить";
    reject.className = "danger";
    reject.addEventListener("click", () => resolveConfirmation(false, confirmationId));
    actions.appendChild(reject);

    els.pendingConfirmation.appendChild(actions);
  }

  async function resolveConfirmation(approve, confirmationId) {
    const result = await api(approve ? "/remote/api/agent/approve" : "/remote/api/agent/reject", {
      method: "POST",
      body: { confirmationId },
    });
    notify(result.message, result.ok ? "ok" : "error");
  }

  function renderIdeSnapshot() {
    if (!state.ideSnapshot) {
      els.ideSnapshot.innerHTML = '<div class="snapshot-card empty">Снимок пока недоступен.</div>';
      return;
    }
    const snapshot = state.ideSnapshot;
    const cards = [
      card(labels.cards.workbench, snapshot.workbench || {}),
      card(labels.cards.editor, snapshot.editor || {}),
      card(labels.cards.diagnostics, snapshot.diagnostics || []),
      card(labels.cards.openViews, snapshot.openViews || []),
    ];
    els.ideSnapshot.innerHTML = "";
    cards.forEach((node) => els.ideSnapshot.appendChild(node));
  }

  function renderCommands() {
    els.commandList.innerHTML = "";
    state.commands.forEach((command) => {
      const option = document.createElement("option");
      option.value = command.id || "";
      option.label = [(command.name || command.id || ""), command.denied ? "[заблокирована]" : ""].join(" ").trim();
      els.commandList.appendChild(option);
    });
  }

  function card(title, payload) {
    const node = document.createElement("article");
    node.className = "snapshot-card";
    node.innerHTML = "<strong>" + escapeHtml(title) + "</strong><pre>" + escapeHtml(prettyLocalized(payload)) + "</pre>";
    return node;
  }

  function renderResult(container, title, payload) {
    const node = document.createElement("article");
    node.className = "result-card";
    node.innerHTML = "<strong>" + escapeHtml(title) + "</strong><pre>" + escapeHtml(prettyLocalized(payload)) + "</pre>";
    container.prepend(node);
    while (container.children.length > 6) {
      container.removeChild(container.lastChild);
    }
  }

  function notify(message, kind) {
    if (!message) {
      return;
    }
    const node = document.createElement("div");
    node.className = ["notice", kind || ""].join(" ").trim();
    node.textContent = message;
    els.noticeBar.innerHTML = "";
    els.noticeBar.appendChild(node);
    window.setTimeout(() => {
      if (els.noticeBar.contains(node)) {
        els.noticeBar.removeChild(node);
      }
    }, 4200);
  }

  async function api(path, options) {
    const opts = options || {};
    const request = {
      method: opts.method || "GET",
      headers: {},
      credentials: "same-origin",
    };
    if (opts.body !== undefined) {
      request.headers["Content-Type"] = "application/json";
      request.body = JSON.stringify(opts.body);
    }
    try {
      const response = await fetch(path, request);
      const text = await response.text();
      const json = text ? JSON.parse(text) : {};
      return {
        ok: response.ok && (json.ok !== false),
        status: response.status,
        payload: json,
        message: json.message || response.statusText || "",
      };
    } catch (error) {
      return {
        ok: false,
        status: 0,
        payload: {},
        message: error && error.message ? error.message : "Запрос не выполнен",
      };
    }
  }

  function parseJsonText(text) {
    if (!text) {
      return {};
    }
    try {
      return JSON.parse(text);
    } catch (error) {
      return null;
    }
  }

  function prettyLocalized(value) {
    return JSON.stringify(localizeValue(value), null, 2);
  }

  function numberValue(raw, fallback) {
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
  }

  function formatTime(value) {
    if (!value) {
      return "";
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleTimeString("ru-RU");
  }

  function localizeEventType(type) {
    return labels.eventTypes[type] || type || "";
  }

  function localizeState(state) {
    return labels.states[state] || state || "";
  }

  function localizeKey(key) {
    return labels.keys[key] || key;
  }

  function localizeValue(value, key) {
    if (Array.isArray(value)) {
      return value.map((item) => localizeValue(item));
    }
    if (value && typeof value === "object") {
      const localized = {};
      Object.keys(value).forEach((itemKey) => {
        localized[localizeKey(itemKey)] = localizeValue(value[itemKey], itemKey);
      });
      return localized;
    }
    if (typeof value === "boolean") {
      return value ? "Да" : "Нет";
    }
    if (typeof value === "string") {
      if (key === "state") {
        return localizeState(value);
      }
      if (key === "mode") {
        return labels.modes[value] || value;
      }
      if (key === "type") {
        return localizeEventType(value);
      }
    }
    return value;
  }

  function cryptoRandom() {
    if (window.crypto && window.crypto.randomUUID) {
      return window.crypto.randomUUID();
    }
    return String(Date.now()) + "-" + Math.random().toString(16).slice(2);
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;");
  }

  init();
})();
