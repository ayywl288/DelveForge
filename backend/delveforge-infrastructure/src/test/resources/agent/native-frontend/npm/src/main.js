import { createApp, h } from 'vue';
import { increment, isDue } from './counter.js';
createApp({ render: () => h('p', { 'data-due': isDue(2) }, 'Next due: ' + increment(2)) }).mount('#app');
