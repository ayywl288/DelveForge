/* 在实际 Node/Vitest 进程入口附加报告，不解析或授权 package.json 的脚本语法。 */
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { spawnSync } = require('node:child_process');

const directory = process.env.DF_TEST_REPORTS;
if (directory && !process.env.NODE_TEST_CONTEXT) {
    let runner;
    let args;
    // 带有本次报告路径的子进程已经装配，避免再次重启；测试 worker 不重复计数。
    const attached = [...process.execArgv, ...process.argv.slice(2)]
        .some(value => value.includes(directory));
    if (!attached && process.execArgv.includes('--test')) {
        runner = 'node-junit';
        args = [...process.execArgv, ...process.argv.slice(1)];
    } else if (!attached && process.argv[1]) {
        let entry;
        try {
            entry = fs.realpathSync(process.argv[1]);
        } catch {
            // 普通 Node 入口的失败由原进程报告，不作为测试运行。
        }
        if (entry && /[/\\]vitest[/\\]vitest\.mjs$/.test(entry)) {
            runner = 'vitest-json';
            args = [...process.execArgv, process.argv[1], ...process.argv.slice(2)];
        }
    }
    if (runner) {
        const id = randomUUID();
        const report = path.join(directory, id + (runner === 'node-junit' ? '.xml' : '.json'));
        const options = runner === 'node-junit'
            ? ['--test-reporter=junit', '--test-reporter-destination=' + report,
               '--test-reporter=spec', '--test-reporter-destination=stdout']
            : ['--run', '--watch=false', '--reporter=json', '--outputFile=' + report];
        // 用户原有报告可另外生成；受控结果使用本次独立文件，不能复用仓库旧报告。
        const filtered = [];
        for (let index = 0; index < args.length; index++) {
            const value = args[index];
            const flags = runner === 'node-junit'
                ? ['--test-reporter', '--test-reporter-destination']
                : ['--reporter', '--outputFile', '--watch'];
            if (flags.includes(value)) {
                if (value !== '--watch') index++;
            } else if (!flags.some(flag => value.startsWith(flag + '=')) && value !== '--watch') {
                filtered.push(value);
            }
        }
        if (runner === 'node-junit') {
            filtered.unshift(...options);
        } else {
            // Vitest 参数放在 -- 之前，保留已有过滤器及 wrapper 选择的工作目录。
            const separator = filtered.indexOf('--');
            filtered.splice(separator < 0 ? filtered.length : separator, 0, ...options);
        }
        const execution = spawnSync(process.execPath, filtered, { stdio: 'inherit', env: process.env });
        const code = Number.isInteger(execution.status) ? execution.status : 1;
        fs.writeFileSync(path.join(directory, id + '.receipt'), JSON.stringify({
            runner, report: path.basename(report), exitCode: code
        }), { flag: 'wx' });
        process.exit(code);
    }
}
