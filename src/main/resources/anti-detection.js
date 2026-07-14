(() => {
  "use strict";

  /* -------------------------------------------------------
   * 0. 基础自动化痕迹（Boss security-check / zp_stoken 前先过）
   * ----------------------------------------------------- */
  try {
    Object.defineProperty(navigator, "webdriver", {
      get: () => undefined,
      configurable: true,
    });
  } catch (_) {}

  try {
    // 清理常见 CDP/自动化残留
    const cdcKeys = Object.getOwnPropertyNames(window).filter((k) =>
      k.match(/^cdc_|__selenium_|__webdriver_|__driver_/)
    );
    cdcKeys.forEach((k) => {
      try {
        delete window[k];
      } catch (_) {}
    });
  } catch (_) {}

  try {
    if (!window.chrome) {
      window.chrome = { runtime: {} };
    } else if (!window.chrome.runtime) {
      window.chrome.runtime = {};
    }
  } catch (_) {}

  try {
    Object.defineProperty(navigator, "languages", {
      get: () => ["zh-CN", "zh"],
      configurable: true,
    });
  } catch (_) {}

  try {
    Object.defineProperty(navigator, "plugins", {
      get: () => [1, 2, 3, 4, 5],
      configurable: true,
    });
  } catch (_) {}

  /* -------------------------------------------------------
   * 1. 保存原生 Function.prototype.toString
   * ----------------------------------------------------- */
  const nativeFunctionToString = Function.prototype.toString;

  /* -------------------------------------------------------
   * 2. WeakMap：函数 → 伪原生源码
   * ----------------------------------------------------- */
  const nativeSourceMap = new WeakMap();

  /* -------------------------------------------------------
   * 3. 注册伪原生源码
   * ----------------------------------------------------- */
  const registerNativeSource = (fn, source) => {
    try {
      nativeSourceMap.set(fn, source);
    } catch (_) {}
  };

  /* -------------------------------------------------------
   * 4. 劫持 Function.prototype.toString
   * ----------------------------------------------------- */
  Object.defineProperty(Function.prototype, "toString", {
    configurable: true,
    writable: true,
    value: function toString() {
      if (nativeSourceMap.has(this)) {
        return nativeSourceMap.get(this);
      }
      return nativeFunctionToString.call(this);
    },
  });

  /* -------------------------------------------------------
   * 5. 伪装 Function.prototype.toString 自身
   * ----------------------------------------------------- */
  registerNativeSource(
    Function.prototype.toString,
    nativeFunctionToString.toString(),
  );

  /* -------------------------------------------------------
   * 6. stealthify：包装函数但保持“原生外观”
   * ----------------------------------------------------- */
  const stealthify = (obj, prop, handler) => {
    const original = obj[prop];
    if (typeof original !== "function") return;

    const wrapped = function (...args) {
      return handler.call(this, original, args);
    };
    const namePropertyDescriptor = Object.getOwnPropertyDescriptor(
      wrapped,
      "name",
    );
    Object.defineProperty(wrapped, "name", {
      ...namePropertyDescriptor,
      value: prop,
    });
    try {
      Object.setPrototypeOf(wrapped, Object.getPrototypeOf(original));
    } catch (_) {}

    registerNativeSource(wrapped, nativeFunctionToString.call(original));

    const desc = Object.getOwnPropertyDescriptor(obj, prop);
    Object.defineProperty(obj, prop, {
      ...desc,
      value: wrapped,
    });
  };

  /* -------------------------------------------------------
   * 7. 过滤 console 展开，降低 CDP/DevTools 暴露
   * ----------------------------------------------------- */
  const filterConsoleArgs = (args) =>
    args.map((arg) => {
      if (arg && typeof arg === "object") {
        return {};
      }
      return arg;
    });

  ["log", "debug", "info", "warn", "error", "dir", "table"].forEach((name) => {
    stealthify(console, name, (original, args) => {
      return original.apply(console, filterConsoleArgs(args));
    });
  });

  registerNativeSource(
    registerNativeSource,
    "function registerNativeSource() { [native code] }",
  );
})();
