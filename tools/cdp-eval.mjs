#!/usr/bin/env node
/**
 * Evaluate one expression in a running WebView / Chromium page.
 *
 *   node tools/cdp-eval.mjs ws://127.0.0.1:9333/devtools/page/<id> "1+1"
 *
 * Used for on-device checks of the overlay WebView. No npm dependencies:
 * Node 22+ ships a global WebSocket.
 */

const [url, expression] = process.argv.slice(2);
if (!url || !expression) {
  console.error("usage: cdp-eval.mjs <ws-url> <expression>");
  process.exit(2);
}

const ws = new WebSocket(url);
const timer = setTimeout(() => {
  console.error("timeout");
  process.exit(1);
}, 10000);

ws.addEventListener("open", () => {
  ws.send(
    JSON.stringify({
      id: 1,
      method: "Runtime.evaluate",
      params: {
        expression,
        returnByValue: true,
        awaitPromise: true,
        userGesture: true,
      },
    }),
  );
});

ws.addEventListener("message", (event) => {
  const msg = JSON.parse(String(event.data));
  if (msg.id !== 1) return;
  clearTimeout(timer);
  if (msg.result?.exceptionDetails) {
    console.error(JSON.stringify(msg.result.exceptionDetails, null, 2));
    process.exitCode = 1;
  } else {
    const value = msg.result?.result?.value;
    console.log(typeof value === "string" ? value : JSON.stringify(value));
  }
  ws.close();
});

ws.addEventListener("error", (event) => {
  clearTimeout(timer);
  console.error("websocket error", event.message || event);
  process.exit(1);
});
