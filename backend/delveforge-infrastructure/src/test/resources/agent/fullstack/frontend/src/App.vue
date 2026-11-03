<script setup lang="ts">
import { ref } from 'vue';
import { next } from './counter';
const value = ref(0);
</script>
<template><button @click="value = next(value)">Increment {{ value }}</button></template>
