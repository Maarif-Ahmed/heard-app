const MAX_LINES = 12;
let liveVisibleLines = 3;
const MAX_TIMELINE = 240;
const finalLinesNode = document.getElementById("finalLines");
const partialNode = document.getElementById("partialLine");
const partialDisplayNode = document.getElementById("partialDisplay");
const captionStage = document.querySelector(".caption-stage");
const connectionChip = document.getElementById("connectionChip");
const speechChip = document.getElementById("speechChip");
const languageChip = document.getElementById("languageChip");
const clockChip = document.getElementById("clockChip");
const settingsToggle = document.getElementById("settingsToggle");
const themePanel = document.getElementById("themePanel");
const bgColorPicker = document.getElementById("bgColorPicker");
const boxColorPicker = document.getElementById("boxColorPicker");
const textColorPicker = document.getElementById("textColorPicker");
const fontScaleRange = document.getElementById("fontScaleRange");
const fontScaleValue = document.getElementById("fontScaleValue");
const highContrastToggle = document.getElementById("highContrastToggle");
const themeReset = document.getElementById("themeReset");
const maxLinesRange = document.getElementById("maxLinesRange");
const maxLinesValue = document.getElementById("maxLinesValue");
const lineHeightRange = document.getElementById("lineHeightRange");
const lineHeightValue = document.getElementById("lineHeightValue");
const letterSpacingRange = document.getElementById("letterSpacingRange");
const letterSpacingValue = document.getElementById("letterSpacingValue");
const showPartialToggle = document.getElementById("showPartialToggle");
const readingModeToggle = document.getElementById("readingModeToggle");
const readingWpmRange = document.getElementById("readingWpmRange");
const readingWpmValue = document.getElementById("readingWpmValue");
const readingPauseBtn = document.getElementById("readingPauseBtn");
const readingBackBtn = document.getElementById("readingBackBtn");
const readingNextBtn = document.getElementById("readingNextBtn");
const readingCatchUpBtn = document.getElementById("readingCatchUpBtn");

const KEY_BG = "caption.theme.bg";
const KEY_BOX = "caption.theme.box";
const KEY_TEXT = "caption.theme.text";
const KEY_SCALE = "caption.theme.scale";
const KEY_HC = "caption.theme.highContrast";
const KEY_READING_MODE = "caption.reading.mode";
const KEY_READING_WPM = "caption.reading.wpm";
const KEY_LINES = "caption.display.lines";
const KEY_LH = "caption.display.lh";
const KEY_LS = "caption.display.ls";
const KEY_PARTIAL = "caption.display.partial";

let liveFinalLines = [];
let displayedLines = [];
let lastPartialText = "";
let renderedFinalKey = "";
let scheduledFinalRender = false;
let reconnectDelayMs = 250;
let socket;

let readingMode = false;
let readingPaused = false;
let readingWpm = 130;
let readingTimeline = [];
let readingCursor = -1;
let readingTimerId = null;

function normalizeHex(value, fallback) {
  const raw = (value || "").trim();
  if (/^#[0-9a-fA-F]{6}$/.test(raw)) {
    return raw.toLowerCase();
  }
  if (/^#[0-9a-fA-F]{3}$/.test(raw)) {
    const short = raw.slice(1).toLowerCase();
    return `#${short[0]}${short[0]}${short[1]}${short[1]}${short[2]}${short[2]}`;
  }
  return fallback;
}

function normalizeScale(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  if (Number.isFinite(parsed) && parsed >= 85 && parsed <= 200) {
    return parsed;
  }
  return fallback;
}

function normalizeWpm(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  if (Number.isFinite(parsed) && parsed >= 80 && parsed <= 220) {
    return parsed;
  }
  return fallback;
}

function normalizeLine(text) {
  return (text || "").replace(/\s+/g, " ").trim();
}

function normalizeLineList(lines) {
  if (!Array.isArray(lines)) {
    return [];
  }
  return lines
    .map((line) => normalizeLine(line))
    .filter((line) => line.length > 0);
}

function hexToRgb(hex) {
  const normalized = normalizeHex(hex, "#000000");
  return {
    r: Number.parseInt(normalized.slice(1, 3), 16),
    g: Number.parseInt(normalized.slice(3, 5), 16),
    b: Number.parseInt(normalized.slice(5, 7), 16)
  };
}

function rgbToHex(rgb) {
  const clamp = (value) => Math.max(0, Math.min(255, Math.round(value)));
  const asHex = (value) => clamp(value).toString(16).padStart(2, "0");
  return `#${asHex(rgb.r)}${asHex(rgb.g)}${asHex(rgb.b)}`;
}

function mixHex(a, b, ratio) {
  const t = Math.max(0, Math.min(1, ratio));
  const left = hexToRgb(a);
  const right = hexToRgb(b);
  return rgbToHex({
    r: left.r + (right.r - left.r) * t,
    g: left.g + (right.g - left.g) * t,
    b: left.b + (right.b - left.b) * t
  });
}

function buildPalette(bg, text) {
  return {
    bgSoft: mixHex(bg, "#ffffff", 0.08),
    panel: mixHex(bg, "#ffffff", 0.12),
    panelSoft: mixHex(bg, "#ffffff", 0.18),
    border: mixHex(bg, "#ffffff", 0.36),
    borderStrong: mixHex(bg, "#ffffff", 0.5),
    textDim: mixHex(text, bg, 0.22),
    muted: mixHex(text, bg, 0.34),
    accent: text
  };
}

function setConnectionState(connected, label) {
  connectionChip.textContent = label;
  connectionChip.classList.toggle("chip-ok", connected);
  connectionChip.classList.toggle("chip-warn", !connected);
  if (captionStage) {
    captionStage.classList.toggle("connected", connected);
  }
}

function renderDisplayedLinesIfChanged() {
  const nextKey = `${readingMode ? "reading" : "live"}\n${displayedLines.join("\n")}`;
  if (nextKey === renderedFinalKey) {
    return;
  }

  renderedFinalKey = nextKey;
  for (let index = 0; index < displayedLines.length; index += 1) {
    const line = displayedLines[index];
    const p = finalLinesNode.children[index] || document.createElement("p");
    const classes = ["final-line"];
    if (readingMode && index === 0) {
      classes.push("reading-current");
    }
    if (readingMode && index > 0) {
      classes.push("reading-next");
    }
    if (!readingMode && index === displayedLines.length - 1) {
      classes.push("live-current");
    }
    if (!readingMode && index < displayedLines.length - 1) {
      classes.push("live-history");
    }
    const nextClassName = classes.join(" ");
    if (p.className !== nextClassName) {
      p.className = nextClassName;
    }
    if (p.textContent !== line) {
      p.textContent = line;
    }
    if (!p.parentNode) {
      p.classList.add("line-enter");
      finalLinesNode.appendChild(p);
    }
  }

  while (finalLinesNode.children.length > displayedLines.length) {
    finalLinesNode.removeChild(finalLinesNode.lastElementChild);
  }

  finalLinesNode.scrollTop = finalLinesNode.scrollHeight;
}

function setDisplayedLines(lines) {
  displayedLines = normalizeLineList(lines).slice(-MAX_LINES);
  if (scheduledFinalRender) {
    return;
  }
  scheduledFinalRender = true;
  window.requestAnimationFrame(() => {
    scheduledFinalRender = false;
    renderDisplayedLinesIfChanged();
  });
}

function renderPartialIfChanged(text) {
  const next = text || "";
  if (next === lastPartialText) {
    return;
  }
  lastPartialText = next;
  if (partialNode) {
    partialNode.textContent = next || " ";
  }
  if (partialDisplayNode) {
    partialDisplayNode.textContent = next;
  }
}

function clearReadingTimer() {
  if (readingTimerId != null) {
    window.clearTimeout(readingTimerId);
    readingTimerId = null;
  }
}

function wordCount(text) {
  const matches = (text || "").match(/[\p{L}\p{N}]+/gu);
  return matches ? matches.length : 0;
}

function estimateDwellMs(text) {
  const words = Math.max(1, wordCount(text));
  const punctuationBoost = /[.!?]\s*$/.test(text)
    ? 500
    : /[,;:]\s*$/.test(text)
      ? 260
      : 0;
  const base = (words / readingWpm) * 60000 + 650 + punctuationBoost;
  return Math.max(1700, Math.min(9500, Math.round(base)));
}

function currentReadingLine() {
  if (readingCursor < 0 || readingCursor >= readingTimeline.length) {
    return "";
  }
  return readingTimeline[readingCursor];
}

function hasReadingNext() {
  return readingCursor >= 0 && readingCursor < readingTimeline.length - 1;
}

function renderLiveView() {
  setDisplayedLines(liveFinalLines.slice(-liveVisibleLines));
}

function renderReadingView() {
  const current = currentReadingLine();
  if (!current) {
    setDisplayedLines([]);
    return;
  }

  const preview = hasReadingNext() ? readingTimeline[readingCursor + 1] : "";
  if (preview) {
    setDisplayedLines([current, preview]);
  } else {
    setDisplayedLines([current]);
  }
}

function trimTimeline() {
  if (readingTimeline.length <= MAX_TIMELINE) {
    return;
  }

  const overflow = readingTimeline.length - MAX_TIMELINE;
  readingTimeline = readingTimeline.slice(overflow);
  if (readingCursor >= 0) {
    readingCursor = Math.max(0, readingCursor - overflow);
  }
}

function findOverlapSuffixPrefix(target, incoming) {
  const maxOverlap = Math.min(target.length, incoming.length);
  for (let overlap = maxOverlap; overlap > 0; overlap -= 1) {
    let matched = true;
    for (let i = 0; i < overlap; i += 1) {
      if (target[target.length - overlap + i] !== incoming[i]) {
        matched = false;
        break;
      }
    }
    if (matched) {
      return overlap;
    }
  }
  return 0;
}

function appendSequenceToTimeline(lines) {
  const incoming = normalizeLineList(lines);
  if (incoming.length === 0) {
    return false;
  }

  const overlap = findOverlapSuffixPrefix(readingTimeline, incoming);
  const additions = incoming.slice(overlap);
  if (additions.length === 0) {
    return false;
  }

  additions.forEach((line) => {
    if (readingTimeline[readingTimeline.length - 1] !== line) {
      readingTimeline.push(line);
    }
  });

  trimTimeline();
  return true;
}

function armReadingTimer(forceReset) {
  if (forceReset) {
    clearReadingTimer();
  }

  if (readingTimerId != null) {
    return;
  }
  if (!readingMode || readingPaused || !hasReadingNext()) {
    return;
  }

  const dwellMs = estimateDwellMs(currentReadingLine());
  readingTimerId = window.setTimeout(() => {
    readingTimerId = null;
    if (!readingMode || readingPaused || !hasReadingNext()) {
      return;
    }
    readingCursor += 1;
    renderReadingView();
    updateReadingUi();
    armReadingTimer(false);
  }, dwellMs);
}

function updateReadingUi() {
  readingModeToggle.checked = readingMode;
  readingWpmRange.value = String(readingWpm);
  readingWpmValue.textContent = `${readingWpm} WPM`;

  const hasItems = readingTimeline.length > 0;
  const disabled = !readingMode;
  readingPauseBtn.disabled = disabled || !hasItems;
  readingBackBtn.disabled = disabled || readingCursor <= 0;
  readingNextBtn.disabled = disabled || !hasReadingNext();
  readingCatchUpBtn.disabled =
    disabled ||
    !hasItems ||
    (readingCursor >= 0 && readingCursor === readingTimeline.length - 1);
  readingPauseBtn.textContent = readingPaused ? "Resume" : "Pause";

  document.body.classList.toggle("reading-pace", readingMode);
}

function setReadingMode(enabled, persist) {
  readingMode = Boolean(enabled);
  readingPaused = false;
  clearReadingTimer();

  if (persist) {
    localStorage.setItem(KEY_READING_MODE, readingMode ? "true" : "false");
  }

  if (readingMode) {
    readingTimeline = normalizeLineList(liveFinalLines);
    readingCursor = readingTimeline.length > 0 ? readingTimeline.length - 1 : -1;
    renderReadingView();
    armReadingTimer(false);
  } else {
    readingTimeline = [];
    readingCursor = -1;
    renderLiveView();
    renderPartialIfChanged("");
  }

  updateReadingUi();
}

function syncLiveFinalLines(frame) {
  if (Array.isArray(frame.finalizedLines) && frame.finalizedLines.length > 0) {
    liveFinalLines = normalizeLineList(frame.finalizedLines).slice(-MAX_LINES);
    return;
  }

  if (frame.type === "FINAL") {
    const next = normalizeLine(frame.text);
    if (!next) {
      return;
    }
    if (liveFinalLines[liveFinalLines.length - 1] !== next) {
      liveFinalLines.push(next);
      if (liveFinalLines.length > MAX_LINES) {
        liveFinalLines = liveFinalLines.slice(-MAX_LINES);
      }
    }
  }
}

function applySettingsFrame(s) {
  if (!s) return;
  if (s.bgHex !== undefined) localStorage.setItem(KEY_BG, "#" + s.bgHex);
  if (s.boxHex !== undefined) localStorage.setItem(KEY_BOX, "#" + s.boxHex);
  if (s.textHex !== undefined) localStorage.setItem(KEY_TEXT, "#" + s.textHex);
  if (s.fontScale !== undefined) localStorage.setItem(KEY_SCALE, String(s.fontScale));
  if (s.highContrast !== undefined) localStorage.setItem(KEY_HC, s.highContrast ? "true" : "false");
  if (s.showPartial !== undefined) localStorage.setItem(KEY_PARTIAL, s.showPartial ? "1" : "0");
  if (s.maxLines !== undefined) localStorage.setItem(KEY_LINES, String(s.maxLines));
  if (s.lineHeight !== undefined) localStorage.setItem(KEY_LH, String(s.lineHeight));
  if (s.letterSpacing !== undefined) localStorage.setItem(KEY_LS, String(s.letterSpacing));
  applyTheme();
  renderLiveView();
}

function applyFrame(frame) {
  if (frame.type === "SETTINGS") {
    applySettingsFrame(frame.settings || null);
    return;
  }

  if (frame.status) {
    speechChip.textContent = `Speech: ${frame.status}`;
  }

  if (frame.languageTag) {
    languageChip.textContent = `Language: ${frame.languageTag}`;
  }

  if (frame.type === "PARTIAL") {
    if (!readingMode) {
      renderPartialIfChanged(frame.text || "");
    }
    return;
  }

  syncLiveFinalLines(frame);

  if (readingMode) {
    if (Array.isArray(frame.finalizedLines) && frame.finalizedLines.length > 0) {
      appendSequenceToTimeline(frame.finalizedLines);
    }
    if (frame.type === "FINAL" && frame.text) {
      appendSequenceToTimeline([frame.text]);
    }

    if (readingCursor < 0 && readingTimeline.length > 0) {
      readingCursor = 0;
    }

    renderReadingView();
    updateReadingUi();
    if (!readingPaused) {
      armReadingTimer(false);
    }
    return;
  }

  if (frame.type === "FINAL") {
    renderPartialIfChanged("");
  } else if (frame.type === "SNAPSHOT") {
    renderPartialIfChanged(frame.text || "");
  }

  renderLiveView();
}

function applyTheme() {
  const bg = normalizeHex(localStorage.getItem(KEY_BG), "#000000");
  const box = normalizeHex(localStorage.getItem(KEY_BOX), bg);
  const text = normalizeHex(localStorage.getItem(KEY_TEXT), "#ffffff");
  const scale = normalizeScale(localStorage.getItem(KEY_SCALE), 100);
  const highContrast = localStorage.getItem(KEY_HC) === "true";
  const lhRaw = Number.parseFloat(localStorage.getItem(KEY_LH) || "1.1");
  const lh = Number.isFinite(lhRaw) && lhRaw >= 1.0 && lhRaw <= 2.5 ? lhRaw : 1.1;
  const lsRaw = Number.parseFloat(localStorage.getItem(KEY_LS) || "0");
  const ls = Number.isFinite(lsRaw) && lsRaw >= 0 && lsRaw <= 0.2 ? lsRaw : 0;
  const showPartial = localStorage.getItem(KEY_PARTIAL) !== "0";
  const linesRaw = Number.parseInt(localStorage.getItem(KEY_LINES) || "3", 10);
  liveVisibleLines = Number.isFinite(linesRaw) && linesRaw >= 1 && linesRaw <= 8 ? linesRaw : 3;

  const palette = buildPalette(bg, text);
  const rootStyle = document.documentElement.style;

  rootStyle.setProperty("--user-bg", bg);
  rootStyle.setProperty("--user-bg-soft", palette.bgSoft);
  rootStyle.setProperty("--user-panel", palette.panel);
  rootStyle.setProperty("--user-panel-soft", palette.panelSoft);
  rootStyle.setProperty("--user-border", palette.border);
  rootStyle.setProperty("--user-border-strong", palette.borderStrong);
  rootStyle.setProperty("--user-text", text);
  rootStyle.setProperty("--user-text-dim", palette.textDim);
  rootStyle.setProperty("--user-muted", palette.muted);
  rootStyle.setProperty("--user-box-bg", box);
  rootStyle.setProperty("--user-accent", palette.accent);
  rootStyle.setProperty("--caption-scale", String(scale / 100));
  rootStyle.setProperty("--caption-lh", String(lh));
  rootStyle.setProperty("--caption-ls", ls === 0 ? "0em" : `${ls}em`);
  document.body.classList.toggle("high-contrast", highContrast);
  document.body.classList.toggle("hide-partial", !showPartial);

  bgColorPicker.value = bg;
  boxColorPicker.value = box;
  textColorPicker.value = text;
  fontScaleRange.value = String(scale);
  fontScaleValue.textContent = `${scale}%`;
  highContrastToggle.checked = highContrast;

  if (maxLinesRange) {
    maxLinesRange.value = String(liveVisibleLines);
    maxLinesValue.textContent = String(liveVisibleLines);
  }
  if (lineHeightRange) {
    const lhInt = Math.round(lh * 100);
    lineHeightRange.value = String(lhInt);
    lineHeightValue.textContent = lh.toFixed(1);
  }
  if (letterSpacingRange) {
    letterSpacingRange.value = String(Math.round(ls * 100));
    letterSpacingValue.textContent = `${ls.toFixed(2)}em`;
  }
  if (showPartialToggle) {
    showPartialToggle.checked = showPartial;
  }
}

function initThemeControls() {
  settingsToggle.addEventListener("click", () => {
    themePanel.classList.toggle("hidden");
  });

  bgColorPicker.addEventListener("input", (event) => {
    localStorage.setItem(KEY_BG, event.target.value);
    applyTheme();
  });

  boxColorPicker.addEventListener("input", (event) => {
    localStorage.setItem(KEY_BOX, event.target.value);
    applyTheme();
  });

  textColorPicker.addEventListener("input", (event) => {
    localStorage.setItem(KEY_TEXT, event.target.value);
    applyTheme();
  });

  fontScaleRange.addEventListener("input", (event) => {
    const scale = normalizeScale(event.target.value, 100);
    localStorage.setItem(KEY_SCALE, String(scale));
    applyTheme();
  });

  highContrastToggle.addEventListener("change", (event) => {
    localStorage.setItem(KEY_HC, event.target.checked ? "true" : "false");
    applyTheme();
  });

  if (maxLinesRange) {
    maxLinesRange.addEventListener("input", (event) => {
      const n = Number.parseInt(event.target.value, 10);
      if (Number.isFinite(n) && n >= 1 && n <= 8) {
        localStorage.setItem(KEY_LINES, String(n));
        applyTheme();
        renderLiveView();
      }
    });
  }

  if (lineHeightRange) {
    lineHeightRange.addEventListener("input", (event) => {
      const lh = Number.parseInt(event.target.value, 10) / 100;
      localStorage.setItem(KEY_LH, String(lh));
      applyTheme();
    });
  }

  if (letterSpacingRange) {
    letterSpacingRange.addEventListener("input", (event) => {
      const ls = Number.parseInt(event.target.value, 10) / 100;
      localStorage.setItem(KEY_LS, String(ls));
      applyTheme();
    });
  }

  if (showPartialToggle) {
    showPartialToggle.addEventListener("change", (event) => {
      localStorage.setItem(KEY_PARTIAL, event.target.checked ? "1" : "0");
      applyTheme();
    });
  }

  themeReset.addEventListener("click", () => {
    [KEY_BG, KEY_BOX, KEY_TEXT, KEY_SCALE, KEY_HC, KEY_LINES, KEY_LH, KEY_LS, KEY_PARTIAL].forEach(
      (k) => localStorage.removeItem(k)
    );
    applyTheme();
  });

  document.addEventListener("click", (event) => {
    if (themePanel.classList.contains("hidden")) {
      return;
    }
    if (themePanel.contains(event.target) || settingsToggle.contains(event.target)) {
      return;
    }
    themePanel.classList.add("hidden");
  });

  applyTheme();
}

function initReadingControls() {
  readingWpm = normalizeWpm(localStorage.getItem(KEY_READING_WPM), 130);
  const savedMode = localStorage.getItem(KEY_READING_MODE) === "true";

  readingModeToggle.addEventListener("change", (event) => {
    setReadingMode(event.target.checked, true);
  });

  readingWpmRange.addEventListener("input", (event) => {
    readingWpm = normalizeWpm(event.target.value, 130);
    localStorage.setItem(KEY_READING_WPM, String(readingWpm));
    updateReadingUi();
    if (readingMode && !readingPaused) {
      armReadingTimer(true);
    }
  });

  readingPauseBtn.addEventListener("click", () => {
    if (!readingMode || readingTimeline.length === 0) {
      return;
    }

    readingPaused = !readingPaused;
    if (readingPaused) {
      clearReadingTimer();
    } else {
      armReadingTimer(true);
    }
    updateReadingUi();
  });

  readingBackBtn.addEventListener("click", () => {
    if (!readingMode || readingCursor <= 0) {
      return;
    }

    readingCursor -= 1;
    renderReadingView();
    updateReadingUi();
    if (!readingPaused) {
      armReadingTimer(true);
    }
  });

  readingNextBtn.addEventListener("click", () => {
    if (!readingMode || !hasReadingNext()) {
      return;
    }

    readingCursor += 1;
    renderReadingView();
    updateReadingUi();
    if (!readingPaused) {
      armReadingTimer(true);
    }
  });

  readingCatchUpBtn.addEventListener("click", () => {
    if (!readingMode || readingTimeline.length === 0) {
      return;
    }

    readingCursor = readingTimeline.length - 1;
    renderReadingView();
    updateReadingUi();
    if (!readingPaused) {
      armReadingTimer(true);
    }
  });

  setReadingMode(savedMode, false);
  updateReadingUi();
}

function initHotkeys() {
  document.addEventListener("keydown", (event) => {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }

    const targetTag = event.target?.tagName?.toLowerCase();
    if (targetTag === "input" || targetTag === "textarea" || targetTag === "select") {
      return;
    }

    if (event.key === " " && readingMode) {
      event.preventDefault();
      readingPauseBtn.click();
      return;
    }

    if (event.key === "ArrowRight" && readingMode) {
      event.preventDefault();
      readingNextBtn.click();
      return;
    }

    if (event.key === "ArrowLeft" && readingMode) {
      event.preventDefault();
      readingBackBtn.click();
      return;
    }

    if (event.key === "+" || event.key === "=") {
      const next = normalizeScale(localStorage.getItem(KEY_SCALE), 100) + 5;
      localStorage.setItem(KEY_SCALE, String(Math.min(200, next)));
      applyTheme();
      return;
    }

    if (event.key === "-") {
      const next = normalizeScale(localStorage.getItem(KEY_SCALE), 100) - 5;
      localStorage.setItem(KEY_SCALE, String(Math.max(85, next)));
      applyTheme();
      return;
    }

    if (event.key.toLowerCase() === "h") {
      const nextHighContrast = localStorage.getItem(KEY_HC) !== "true";
      localStorage.setItem(KEY_HC, nextHighContrast ? "true" : "false");
      applyTheme();
    }
  });
}

function connect() {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  const target = `${protocol}://${window.location.host}/ws`;
  socket = new WebSocket(target);

  setConnectionState(false, "Connecting");

  socket.onopen = () => {
    reconnectDelayMs = 250;
    setConnectionState(true, "Live");
  };

  socket.onmessage = (event) => {
    try {
      const frame = JSON.parse(event.data);
      applyFrame(frame);
    } catch (_) {
      setConnectionState(false, "Data Error");
    }
  };

  socket.onclose = () => {
    setConnectionState(false, "Reconnecting");
    window.setTimeout(connect, reconnectDelayMs);
    reconnectDelayMs = Math.min(reconnectDelayMs * 1.6, 2500);
  };

  socket.onerror = () => {
    setConnectionState(false, "Connection Error");
  };
}

function startClock() {
  function tick() {
    const now = new Date();
    clockChip.textContent = now.toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit"
    });
  }
  tick();
  window.setInterval(tick, 1000);
}

function applyUrlParams() {
  let params;
  try {
    params = new URLSearchParams(window.location.search);
  } catch (_) {
    return;
  }
  if (!params.toString()) return;

  if (params.has("bg")) localStorage.setItem(KEY_BG, `#${params.get("bg").replace(/^#/, "")}`);
  if (params.has("box")) localStorage.setItem(KEY_BOX, `#${params.get("box").replace(/^#/, "")}`);
  if (params.has("text")) localStorage.setItem(KEY_TEXT, `#${params.get("text").replace(/^#/, "")}`);
  if (params.has("scale")) {
    const s = normalizeScale(params.get("scale"), null);
    if (s !== null) localStorage.setItem(KEY_SCALE, String(s));
  }
  if (params.has("hc")) localStorage.setItem(KEY_HC, params.get("hc") === "1" ? "true" : "false");
  if (params.has("partial")) localStorage.setItem(KEY_PARTIAL, params.get("partial") === "1" ? "1" : "0");
  if (params.has("lines")) {
    const n = Number.parseInt(params.get("lines"), 10);
    if (Number.isFinite(n) && n >= 1 && n <= 8) localStorage.setItem(KEY_LINES, String(n));
  }
  if (params.has("lh")) {
    const lh = Number.parseFloat(params.get("lh"));
    if (Number.isFinite(lh) && lh >= 1.0 && lh <= 2.5) localStorage.setItem(KEY_LH, String(lh));
  }
  if (params.has("ls")) {
    const ls = Number.parseFloat(params.get("ls"));
    if (Number.isFinite(ls) && ls >= 0 && ls <= 0.2) localStorage.setItem(KEY_LS, String(ls));
  }
}

applyUrlParams();
initThemeControls();
initReadingControls();
initHotkeys();
startClock();
connect();
