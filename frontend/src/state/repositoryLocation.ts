/** 浏览器只作词法提示；真实目标、并发和历史重复由后端检查。 */
export function repositoryLocationKey(location: string): string {
  let value = location.trim().replaceAll("\\", "/");
  const windows = /^[a-zA-Z]:\//.test(value) || value.startsWith("//");
  const prefix = value.startsWith("//") ? "//" : /^[a-zA-Z]:\//.test(value) ? value.slice(0, 3) : "/";
  const parts: string[] = [];
  for (const part of value.slice(prefix.length).split(/\/+/)) {
    if (!part || part === ".") continue;
    if (part === "..") {
      if (parts.length > (prefix === "//" ? 2 : 0)) parts.pop();
    } else parts.push(part);
  }
  value = prefix + parts.join("/");
  return windows ? value.toLowerCase() : value;
}
