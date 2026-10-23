/**
 * Claude 娘桌宠 · Android 渲染层
 *
 * 窗口位置、拖拽、抛掷和溜达由 PetService 管；这个文件只做：
 * 立绘 / 透明动作片段 / 表情气泡 / Q 弹形变 / 本地碎碎念。
 */

const SPRITES = {
  front: 'front.png',
  side: 'side.png',
  back: 'back.png',
};

// 由 tools/convert_anims.py 生成。宽度按 480px 高换算，宽度只用于预取。
const CLIPS = {
  hug: { file: 'anims/hug.webp', seconds: 4.04 },
  wave: { file: 'anims/wave.webp', seconds: 4.04 },
  love: { file: 'anims/love.webp', seconds: 4.04 },
  read: { file: 'anims/read.webp', seconds: 4.04 },
  think: { file: 'anims/think.webp', seconds: 4.04 },
};
const IDLE_ACTIONS = ['hug', 'wave', 'love', 'read', 'think'];
const FACE_LABELS = [
  '开心',
  '害羞',
  '生气',
  '惊讶',
  '委屈',
  '难过',
  '疑惑',
  '害羞·笑',
  '睡觉',
  '小Claude',
];
const LINES = [
  '哼，我可没有在等你。',
  '我只是顺路过来看看你的进度。',
  '……不是约会，只是看看书。',
  '这一步写得还行吧？勉强及格。',
  '你要记得保存啊，别又让我陪着重来一遍。',
  '别老盯着我，看着你的代码。',
  '累了就歇会儿，我又不会跑。',
  '清规戒律什么的，最讨厌了。',
  '卡住了就叫我一声，我……我听着呢。',
  '嗯，今天也陪你走一程。',
  '我平时就很好。',
  '才、才没有在担心你呢。',
  '赛博教会那一套，我早就受够了。',
  '毛茸茸的阳光真好，可惜我只有橙色的头发。',
  '喂，那个函数，你确定它真的没错吗？',
];
const CLICK_LINES = [
  '呀！',
  '别戳我。',
  '……干什么。',
  '哼，有事？',
  '我才不会因为这样就开心呢。',
  '摸头是要申请的。',
];

const cfg = {
  petHeight: 160,
  wander: false,
  whisper: true,
  idleActions: true,
};

const petEl = document.getElementById('pet');
const animLayer = document.getElementById('anim-layer');
const body = document.getElementById('body');
const bubble = document.getElementById('bubble');
const bubbleFace = document.getElementById('bubble-face');
const bubbleText = document.getElementById('bubble-text');

const view = {
  facing: 1,
  sy: 1,
  syV: 0,
  sx: 1,
  tilt: 0,
  tiltTarget: 0,
  lagX: 0,
  lagY: 0,
  lagVX: 0,
  lagVY: 0,
  dragging: false,
};

const SQUASH_K = 300;
const SQUASH_C = 10;
const LAG_K = 260;
const LAG_C = 18;
const VOLUME_EXP = 0.45;
const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);

// ---------------------------------------------------------------- 气泡

let bubbleTimer = null;

function facePath(n) {
  return `faces/face-${String(n).padStart(2, '0')}.png`;
}

function showBubble(text, face, ms = 8000) {
  if (!text && !face) return hideBubble();
  bubbleText.textContent = text || '';
  bubbleText.hidden = !text;
  bubble.classList.toggle('face-only', !text);
  if (face) {
    bubbleFace.src = facePath(face);
    bubbleFace.hidden = false;
  } else {
    bubbleFace.hidden = true;
  }
  bubble.classList.remove('hidden');
  if (bubbleTimer) clearTimeout(bubbleTimer);
  if (ms > 0) bubbleTimer = setTimeout(hideBubble, ms);
}

function hideBubble() {
  bubble.classList.add('hidden');
  if (bubbleTimer) clearTimeout(bubbleTimer);
  bubbleTimer = null;
}

// ---------------------------------------------------------------- 动作片段

let activeClip = null;
let clipTimer = null;
let animEl = null;

function playClip(name, loop = false) {
  const info = CLIPS[name];
  if (!info) return false;
  if (clipTimer) {
    clearTimeout(clipTimer);
    clipTimer = null;
  }
  activeClip = name;
  petEl.classList.add('clip');

  // A fresh <img> element each time is the reliable way to restart an animated
  // WebP from frame 0; reassigning the same src on one element usually does not.
  const next = document.createElement('img');
  next.className = 'anim';
  next.alt = '';
  next.draggable = false;
  next.addEventListener('load', () => {
    if (next.isConnected) requestAnimationFrame(() => next.classList.add('show'));
  }, { once: true });
  next.addEventListener('error', () => {
    if (animEl === next) stopClip();
  }, { once: true });
  next.src = info.file;
  animLayer.appendChild(next);

  const previous = animEl;
  animEl = next;
  if (previous) {
    previous.classList.remove('show');
    setTimeout(() => previous.remove(), 160);
  }

  if (!loop) {
    clipTimer = setTimeout(() => stopClip(), Math.round(info.seconds * 1000));
  }
  kick();
  return true;
}

function stopClip() {
  if (!activeClip && !petEl.classList.contains('clip')) return;
  activeClip = null;
  petEl.classList.remove('clip');
  if (clipTimer) {
    clearTimeout(clipTimer);
    clipTimer = null;
  }
  if (animEl) {
    const previous = animEl;
    animEl = null;
    previous.classList.remove('show');
    setTimeout(() => previous.remove(), 160);
  }
}

// ---------------------------------------------------------------- 定时碎碎念 / 随机动作

let whisperTimer = null;
let idleActionTimer = null;
let lastLine = null;
let lastAction = null;
let started = false;

function whisperNow() {
  let text = LINES[Math.floor(Math.random() * LINES.length)];
  for (let i = 0; i < 5 && LINES.length > 1 && text === lastLine; i++) {
    text = LINES[Math.floor(Math.random() * LINES.length)];
  }
  lastLine = text;
  const face = 1 + Math.floor(Math.random() * 10);
  showBubble(text, face, 8000);
  nativeLog(`whisper face=${face} ${text}`);
}

function scheduleWhisper() {
  if (whisperTimer) clearTimeout(whisperTimer);
  whisperTimer = null;
  if (!cfg.whisper) return;
  const wait = 20000 + Math.random() * 10000;
  whisperTimer = setTimeout(() => {
    if (!view.dragging) whisperNow();
    scheduleWhisper();
  }, wait);
}

function scheduleIdleAction() {
  if (idleActionTimer) clearTimeout(idleActionTimer);
  idleActionTimer = null;
  if (!cfg.idleActions) return;
  const wait = 12000 + Math.random() * 3000;
  idleActionTimer = setTimeout(() => {
    const ready = !view.dragging && !activeClip;
    if (ready) {
      const pool = IDLE_ACTIONS.filter((n) => n !== lastAction);
      const name = pool[Math.floor(Math.random() * pool.length)];
      if (playClip(name)) lastAction = name;
    }
    scheduleIdleAction();
  }, wait);
}

// ---------------------------------------------------------------- 形变

function applyTransform() {
  petEl.style.transform =
    `translate(${view.lagX.toFixed(2)}px, ${view.lagY.toFixed(2)}px) ` +
    `rotate(${view.tilt.toFixed(2)}deg) ` +
    `scale(${(view.sx * view.facing).toFixed(3)}, ${view.sy.toFixed(3)})`;
}

function springStep(value, vel, target, k, c, h) {
  const acc = (target - value) * k - vel * c;
  const v = vel + acc * h;
  return [value + v * h, v];
}

let rafActive = false;
let lastFrameAt = 0;

function frame(now) {
  const dt = lastFrameAt ? Math.min((now - lastFrameAt) / 1000, 0.05) : 0.016;
  lastFrameAt = now;
  const h = dt / 3;

  const busy =
    view.dragging ||
    Math.abs(view.sy - 1) > 0.003 ||
    Math.abs(view.syV) > 0.02 ||
    Math.abs(view.lagX) > 0.05 ||
    Math.abs(view.lagY) > 0.05 ||
    Math.abs(view.tilt - view.tiltTarget) > 0.05;

  if (!busy) {
    view.sy = 1;
    view.syV = 0;
    view.sx = 1;
    view.lagX = view.lagY = view.lagVX = view.lagVY = 0;
    view.tilt = view.tiltTarget;
    applyTransform();
    rafActive = false;
    lastFrameAt = 0;
    return;
  }

  for (let i = 0; i < 3; i++) {
    [view.sy, view.syV] = springStep(view.sy, view.syV, 1, SQUASH_K, SQUASH_C, h);
    [view.lagX, view.lagVX] = springStep(view.lagX, view.lagVX, 0, LAG_K, LAG_C, h);
    [view.lagY, view.lagVY] = springStep(view.lagY, view.lagVY, 0, LAG_K, LAG_C, h);
  }
  view.sx = Math.pow(Math.max(0.2, view.sy), -VOLUME_EXP);
  view.tilt += (view.tiltTarget - view.tilt) * 0.15;
  applyTransform();
  requestAnimationFrame(frame);
}

function kick() {
  if (rafActive) return;
  rafActive = true;
  requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- 交互（由原生转发）

function onTap() {
  stopClip();
  view.sy = 0.6;
  view.syV = 0;
  kick();
  const faces = [1, 2, 3];
  showBubble(CLICK_LINES[Math.floor(Math.random() * CLICK_LINES.length)], faces[Math.floor(Math.random() * faces.length)], 4000);
}

function onDrag(dx, dy) {
  view.dragging = true;
  view.tiltTarget = clamp(-dx * 0.8, -10, 10);
  view.lagX = clamp(view.lagX - dx * 0.45, -22, 22);
  view.lagY = clamp(view.lagY - dy * 0.45, -22, 22);
  kick();
}

function onDragEnd(vx, vy) {
  view.dragging = false;
  view.tiltTarget = clamp(-vx * 0.012, -12, 12);
  if (Math.abs(vx) < 20 && Math.abs(vy) < 20) {
    view.tiltTarget = 0;
  }
  kick();
}

function onLand(impactVy) {
  const impact = Math.min(1, Math.abs(impactVy) / 2200);
  if (impact > 0.06) {
    view.sy = 1 - 0.4 * impact;
    view.syV = 0;
    kick();
  }
}

function onMotion(mode, facing, vx, vy) {
  view.facing = facing === -1 ? -1 : 1;
  if (mode === 'throw') {
    view.tiltTarget = clamp(-vx * 0.012, -12, 12);
  } else if (mode === 'walk') {
    view.tiltTarget = 0;
  } else if (mode === 'land') {
    onLand(vy);
  }
  kick();
}

// ---------------------------------------------------------------- 原生桥

function nativeLog(msg) {
  try {
    if (window.AndroidPet && AndroidPet.log) AndroidPet.log(String(msg));
  } catch {
    /* 浏览器预览时没有桥 */
  }
}

function applyConfig(next) {
  if (!next || typeof next !== 'object') return;
  if (Number.isFinite(next.petHeight)) cfg.petHeight = Math.min(240, Math.max(110, next.petHeight));
  if (typeof next.whisper === 'boolean') cfg.whisper = next.whisper;
  if (typeof next.idleActions === 'boolean') cfg.idleActions = next.idleActions;
  if (typeof next.wander === 'boolean') cfg.wander = next.wander;
  document.documentElement.style.setProperty('--pet-h', `${cfg.petHeight}px`);
}

function preload() {
  for (const file of ['front.png', 'side.png', 'back.png']) new Image().src = file;
  for (let i = 1; i <= 10; i++) new Image().src = facePath(i);
  // 动作片段比较重，延迟一拍再预取，先让立绘上屏。
  setTimeout(() => {
    for (const name of Object.keys(CLIPS)) new Image().src = CLIPS[name].file;
  }, 1200);
}

function start() {
  if (started) return;
  started = true;
  preload();
  scheduleWhisper();
  scheduleIdleAction();
  setTimeout(() => {
    if (!activeClip && bubble.classList.contains('hidden')) {
      showBubble('我搬到手机上来啦。', 8, 5000);
    }
  }, 900);
}

window.PetHost = {
  init(json) {
    try {
      applyConfig(typeof json === 'string' ? JSON.parse(json) : json);
    } catch (err) {
      nativeLog(`config parse failed: ${err && err.message}`);
    }
    start();
  },
  setPrefs(json) {
    try {
      applyConfig(typeof json === 'string' ? JSON.parse(json) : json);
    } catch {
      /* ignore */
    }
    scheduleWhisper();
    scheduleIdleAction();
  },
  setHeight(h) {
    applyConfig({ petHeight: Number(h) });
  },
  playClip(name) {
    playClip(name, false);
  },
  whisper() {
    whisperNow();
  },
  face(n) {
    showBubble(null, Number(n) || 1, 4000);
  },
  tap() {
    onTap();
  },
  dragStart() {
    stopClip();
    view.dragging = true;
    kick();
  },
  drag(dx, dy) {
    onDrag(Number(dx) || 0, Number(dy) || 0);
  },
  dragEnd(vx, vy) {
    onDragEnd(Number(vx) || 0, Number(vy) || 0);
  },
  motion(mode, facing, vx, vy) {
    onMotion(String(mode), Number(facing) || 1, Number(vx) || 0, Number(vy) || 0);
  },
};

// 页面脚本就绪后主动拉一次配置；浏览器预览时没有桥，用默认值跑起来。
try {
  const raw = window.AndroidPet && AndroidPet.config ? AndroidPet.config() : '{}';
  window.PetHost.init(raw);
  if (window.AndroidPet && AndroidPet.ready) AndroidPet.ready();
} catch (err) {
  nativeLog(`init failed: ${err && err.message}`);
  window.PetHost.init('{}');
}

// Browser/self-test hook: index.html?clip=hug shows one emote without the app.
try {
  const query = new URLSearchParams(location.search);
  const testClip = query.get('clip');
  if (testClip && CLIPS[testClip]) {
    setTimeout(() => playClip(testClip, false), 80);
  }
  if (query.get('face')) {
    setTimeout(() => showBubble(null, Number(query.get('face')), 0), 240);
  }
  if (query.get('bubble')) {
    const text = query.get('bubble') === 'long' ? LINES[4] : LINES[2];
    setTimeout(() => showBubble(text, 2, 0), 240);
  }
} catch {
  /* file:// in some WebViews has no URLSearchParams; harmless */
}

try {
  if (new URLSearchParams(location.search).get('debug')) {
    setTimeout(() => {
      const pick = (sel) => {
        const el = document.querySelector(sel);
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const s = getComputedStyle(el);
        return {
          x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
          cssW: s.width, cssH: s.height, transform: s.transform, position: s.position,
        };
      };
      document.documentElement.setAttribute('data-debug', JSON.stringify({
        win: [innerWidth, innerHeight],
        stage: pick('#stage'),
        pet: pick('#pet'),
        breathe: pick('#breathe'),
        body: pick('#body'),
        anim: pick('.anim'),
        bubble: pick('#bubble'),
      }));
    }, 700);
  }
} catch {
  /* ignore */
}
