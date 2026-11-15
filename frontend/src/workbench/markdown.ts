import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'

const markdown = new MarkdownIt({
    html: false,
    linkify: false,
    highlight(code, language) {
        if (!language || !hljs.getLanguage(language)) return ''
        return hljs.highlight(code, { language, ignoreIllegals: true }).value
    },
})

markdown.validateLink = href => {
    if (!/^https?:\/\//i.test(href)) return false
    try {
        const url = new URL(href)
        return Boolean(url.hostname) && !url.username && !url.password
    } catch {
        return false
    }
}

// 模型可生成链接文字，只有用户点击才能发起外部请求。
markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(tokens[index]!.content)
markdown.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
    tokens[index]!.attrSet('target', '_blank')
    tokens[index]!.attrSet('rel', 'noopener noreferrer')
    return renderer.renderToken(tokens, index, options)
}

const renderFence = markdown.renderer.rules.fence!
markdown.renderer.rules.fence = (tokens, index, options, env, renderer) => {
    const language = tokens[index]!.info.trim().split(/\s+/)[0] || '代码'
    const header = '<div class="code-heading"><span>' + markdown.utils.escapeHtml(language)
        + '</span><button type="button" data-copy-code>复制代码</button></div>'
    return '<div class="code-block">' + header + renderFence(tokens, index, options, env, renderer) + '</div>'
}

export function renderAssistant(text: string): string {
    return markdown.render(text)
}
