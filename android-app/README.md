# NetSniffer (Android APK)

A WebView-based Android app that opens any user-entered URL and captures every network request the page makes. Two tabs:

- **Browser** — URL bar + WebView
- **Network** — live list of intercepted requests (URL, method, status, mime, size, time)

## Build

Every push to `main` that touches `android-app/**` triggers `.github/workflows/android-build.yml`. The APK is uploaded as a workflow artifact and attached to a GitHub Release named `android-build-<number>`.

## Local build (optional)

```bash
cd android-app
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Android SDK (compileSdk 34).

## How network capture works

`SniffingWebViewClient.shouldInterceptRequest()` intercepts every HTTP/HTTPS request the WebView makes, re-runs it through OkHttp, records the metadata in `CaptureStore`, and returns the response to the WebView. Page-level hooks capture fetch, XHR, beacon and WebSocket frames when JavaScript is available.

For WebSocket traffic that the WebView/DevTools path misses, **Auto WS Proxy** is enabled by default. New `ws://` / `wss://` connections are rewritten through the VPS relay endpoint, with the original target added in `?target=...`, so the server can record frames before forwarding them back to the page.
