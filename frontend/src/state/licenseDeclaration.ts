// 与 SoftwareAsset 新提交校验一致：未知是 null；文字不代表许可真实或法律兼容。
export function validLicenseDeclaration(value: string | null): boolean {
  return value === null || /\p{L}/u.test(value);
}

export const standardLicenses = ["MIT", "Apache-2.0", "BSD-3-Clause", "GPL-3.0-only"] as const;
