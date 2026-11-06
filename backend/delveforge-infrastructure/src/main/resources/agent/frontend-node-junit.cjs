/* Node 内置 JUnit 不输出 file；用真实运行事件补充 classname，保留内置报告的计数与失败语义。 */
'use strict';
const { junit } = require('node:test/reporters');

module.exports = async function* report(source) {
    async function* withSource() {
        for await (const event of source) {
            if ((event.type === 'test:pass' || event.type === 'test:fail') && event.data.file) {
                yield { ...event, data: { ...event.data, classname: event.data.file } };
            } else {
                yield event;
            }
        }
    }
    yield* junit(withSource());
};
