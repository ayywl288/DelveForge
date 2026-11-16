import { createHash, randomUUID } from 'node:crypto'
import { existsSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve, sep } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const [root, project, action, name = '', expected = ''] = process.argv.slice(2)
const read = path => JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''))
const hash = value => createHash('sha256').update(value).digest('hex')
const python = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'packages.py'), 'utf8')
const registry = join(root, 'tool-images.json')
const validDigest = /^sha256:[a-f0-9]{64}$/
let endpoint = ''

function docker(args, input, notice = 'Docker 操作未成功') {
    const result = spawnSync('docker', ['--host', endpoint, ...args], {
        input, encoding: 'utf8', timeout: 285000, maxBuffer: 4 * 1024 * 1024, windowsHide: true,
    })
    if (result.error || result.status !== 0) throw Error(notice)
    return result.stdout.trim()
}

function guest(args, mode, specs, notice) {
    return JSON.parse(docker([...args, '-', mode, ...(specs ? [JSON.stringify(specs)] : [])], python, notice))
}

function work() {
    if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(project)) throw Error('项目 ID 无效')
    const catalog = read(join(root, 'runtime-catalog.json'))
    endpoint = catalog.endpoint
    if (!/^npipe:\/\/\/\/\.\/pipe\/[A-Za-z0-9_-]+$/.test(endpoint)) throw Error('本机 Docker 引擎登记无效')
    const base = catalog.images.development
    const owner = catalog.owner
    const releases = existsSync(registry) ? read(registry).releases : []
    const latest = releases.filter(release => release.base === base).at(-1) ?? { version: 0, image: base, packages: [], locks: [] }
    if (!validDigest.test(base) || !validDigest.test(latest.image)) throw Error('镜像身份无效')
    const file = join(root, 'environments', project, 'environment.json')
    if (!existsSync(file)) {
        if (action !== 'inventory') throw Error('该项目尚未初始化开发环境')
        return { initialized: false, latest: latest.version, packages: latest.packages }
    }
    const env = read(file)
    if (env.installation !== owner || env.project !== project || !validDigest.test(env.image)) throw Error('项目环境归属待核对')
    const current = releases.find(release => release.image === env.image)?.version ?? 0
    const status = { initialized:true, current, latest:latest.version, image:env.image, latestImage:latest.image,
        rollback:!!env.previousOwner, packages:latest.packages }
    const present = docker(['ps','-a','--no-trunc','--filter','id='+env.containerId,'--format','{{.ID}}'])
    if (present !== env.containerId) {
        if (action === 'inventory') return { ...status, missing:true }
        throw Error('原开发容器已缺失，请在项目环境中选择重建')
    }
    const labels = JSON.parse(docker(['inspect','--format','{{json .Config.Labels}}',env.containerId]))
    if (labels['dev.delveforge.project'] !== project || labels['dev.delveforge.workbench'] !== env.owner ||
        labels['dev.delveforge.installation'] !== owner) throw Error('项目容器身份待核对')
    const projectPython = ['exec','-i',env.containerId,'/usr/bin/python3']
    const isolated = ['run','--rm','-i','--label','dev.delveforge.installation='+owner,'--network','none','--cap-drop','ALL']
    if (action === 'status') return status
    if (docker(['inspect','--format','{{.State.Running}}',env.containerId]) !== 'true') {
        if (action === 'inventory') return { ...status, stopped:true }
        throw Error('请先启动该项目环境')
    }
    const observed = guest(projectPython,'inventory')
    const original = guest([...isolated,'--entrypoint','python3',env.image],'inventory')
    const included = new Map(original.packages.map(p => [p.name + ':' + p.arch, p.version]))
    const rows = observed.packages.map(pkg => ({ ...pkg, included:included.get(pkg.name + ':' + pkg.arch) === pkg.version }))
    const manualFiles = observed.local.filter(command => !original.local.includes(command))
    if (action === 'inventory') return { ...status, distribution:observed.distribution, installed:rows, manualFiles }
    if (!['preview','publish'].includes(action) || !/^[a-z0-9][a-z0-9+.-]{1,127}$/.test(name)) throw Error('请选择一个 APT 包')
    const candidates = rows.filter(p => p.name === name && !p.included && p.manual)
    if (candidates.length !== 1) throw Error('请选择项目额外安装且架构明确的手动 APT 包')
    const pkg = candidates[0]
    if (latest.packages.some(p => p.name === name && p.version === pkg.version && p.arch === pkg.arch)) throw Error('此版本已在全局镜像中')
    const existing = latest.packages.filter(p => p.name !== name)
    const requested = [...existing, pkg].map(p => ({...p, selected:true}))
    const specs = [...requested, ...latest.locks.filter(p => !requested.some(q => p.name === q.name && p.arch === q.arch))]
    const parent = join(root, 'tool-builds', project)
    mkdirSync(parent,{recursive:true})
    const pointer = join(parent,'selection.json')
    function discard(dir) {
        if (/^build-[a-zA-Z0-9]+$/.test(dir) && resolve(parent,dir).startsWith(resolve(parent)+sep)) {
            rmSync(join(parent,dir),{recursive:true,force:true})
        }
    }
    const saved = existsSync(pointer) ? read(pointer) : null
    if (action === 'publish' && (!saved || !/^[a-f0-9]{64}$/.test(expected) || saved.plan !== expected ||
            saved.base !== base || saved.prior !== latest.image || saved.selected.name !== name ||
            saved.selected.arch !== pkg.arch || saved.selected.version !== pkg.version || !/^build-[a-zA-Z0-9]+$/.test(saved.dir))) {
        throw Error('推广预览已变更，请重新核对')
    }
    const temp = action === 'preview' ? mkdtempSync(join(parent,'build-')) : join(parent,saved.dir)
    const archives = join(temp,'archives')
    let keep = action === 'publish'
    try {
        let selected, plan, locks, approved
        if (action === 'preview') {
            mkdirSync(archives)
            const clean = ['run','--rm','-i','--label','dev.delveforge.installation='+owner,'--network',env.owner+'-inside',
            '--security-opt','no-new-privileges','--mount','type=bind,src='+archives+',target=/artifacts',
            '-e','http_proxy=http://egress:3128','-e','https_proxy=http://egress:3128',
            '--entrypoint','python3',base]
            locks = guest(clean,'download',specs,'无法从基础镜像的签名 APT 源取得指定版本及依赖，或包中缺少可验证的命令')
            approved = requested.map(p => locks.find(a => a.name === p.name && a.arch === p.arch))
            selected = approved.find(p => p.name === name)
            plan = hash(JSON.stringify({ project, base, prior:latest.image, selected, locks }))
        } else {
            selected = saved.selected
            plan = saved.plan
            locks = saved.locks
            approved = saved.approved
            const listed = locks.map(a=>a.sha256 + '.deb').sort()
            if (JSON.stringify(readdirSync(archives).sort()) !== JSON.stringify(listed)) throw Error('归档清单已变化，请重新预览')
            for (const archive of locks) {
                if (!/^[a-f0-9]{64}$/.test(archive.sha256) || hash(readFileSync(join(archives,archive.sha256+'.deb'))) !== archive.sha256) {
                    throw Error('归档和预览校验值不符，请重新核对')
                }
            }
        }
        if (!selected?.command || observed.distribution !== selected.distribution || !guest(projectPython,'match',[selected],'无法核对项目中安装的发行版命令')) {
            throw Error('无法确认安装记录、文件清单和可执行文件与受信发行版一致')
        }
        const result = { package:selected, plan, current:latest.version, dependencies:locks.map(p=>p.name) }
        if (action === 'preview') {
            const dir = temp.slice(parent.length + 1)
            const draft = pointer + '.' + randomUUID() + '.tmp'
            writeFileSync(draft,JSON.stringify({dir,base,prior:latest.image,selected,plan,locks,approved}),{flag:'wx'})
            renameSync(draft,pointer)
            keep = true
            for (const entry of readdirSync(parent)) if (entry !== dir) discard(entry)
            return result
        }
        const tag = 'df-workbench/trusted-base:'+base.slice(7,31)
        docker(['tag',base,tag])
        // 所有归档来自干净下载容器。构建上下文只有生成的 Dockerfile 和这些已校验的 .deb。
        writeFileSync(join(temp,'Dockerfile'), 'FROM '+tag+'\nCOPY archives/ /tmp/delveforge-apt/\nRUN dpkg -i /tmp/delveforge-apt/*.deb && dpkg --audit && rm -rf /tmp/delveforge-apt\n')
        const version = Math.max(0,...releases.map(r=>r.version)) + 1
        const image = docker(['build','--network','none','--pull=false','--label','dev.delveforge.tool-image='+owner,'-q',temp],
            undefined,'离线安装未成功，全局镜像仍为原版本').split(/\r?\n/).at(-1)
        if (!validDigest.test(image) || !guest([...isolated,'--entrypoint','python3',image],'verify',approved,'新镜像命令验证失败')) {
            throw Error('新镜像中的包或命令未通过验证')
        }
        const release = {version, base, image, packages:approved, locks, createdAt:new Date().toISOString()}
        docker(['tag',image,'df-workbench/tools-'+owner+':v'+version+'-'+image.slice(7,19)])
        rmSync(pointer)
        keep = false
        return { ...result, version, image, release, prior:latest.image }
    } finally {
        if (!keep) discard(temp.slice(parent.length+1))
    }
}

try {
    delete process.env.DEEPSEEK_API_KEY
    process.stdout.write(JSON.stringify(work()))
} catch (error) {
    process.stdout.write(JSON.stringify({error:error.message}))
    process.exitCode=1
}
