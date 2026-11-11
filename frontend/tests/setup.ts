import { afterEach, beforeEach, vi } from "vitest";
import { installWebLocks } from "./webLocks";

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  document.body.innerHTML = "";
  // jsdom 不实现原生 dialog；真实浏览器的焦点、Escape 和布局另行检查。
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
  installWebLocks();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
