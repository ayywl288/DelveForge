import type { CatalogModel, ModelChoice } from './api'

interface Preference extends ModelChoice { favorite: boolean }
const key = 'df.model.preferences.v1'
export function modelPreferences(): Preference[] {
    try {
        const rows: unknown = JSON.parse(localStorage.getItem(key) ?? '[]')
        return Array.isArray(rows) ? rows.filter(row => row && typeof row.provider === 'string'
            && typeof row.model === 'string' && typeof row.effort === 'string') : []
    } catch { return [] }
}

export function rememberModel(choice: ModelChoice, favorite?: boolean) {
    const rows = modelPreferences()
    const same = (row: ModelChoice) => row.provider === choice.provider && row.model === choice.model
    const item = { ...choice, favorite: favorite ?? rows.find(same)?.favorite ?? false }
    try { localStorage.setItem(key, JSON.stringify([item, ...rows.filter(row => !same(row))])) }
    catch { /* 浏览器禁用存储时，对话中的选择仍然有效。 */ }
}

export function preferredEffort(model: CatalogModel, remembered?: string) {
    const supported = model.efforts.filter(level => level !== 'off' && !model.rejectedEfforts.includes(level))
    if (remembered && supported.includes(remembered)) return remembered
    return supported.includes('default') ? 'default' : undefined
}
