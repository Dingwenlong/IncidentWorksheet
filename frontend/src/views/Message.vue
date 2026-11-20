<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { CircleAlert } from 'lucide-vue-next'
import { home } from '../router'
const route = useRoute(),
  offline = computed(() => route.name === 'connection')
const title = computed(() =>
  offline.value ? '暂时无法连接服务' : route.name === 'forbidden' ? '无权访问此页面' : '未找到页面'
)
function reload() {
  location.assign('/')
}
</script>
<template>
  <section class="blank-view">
    <CircleAlert :size="34" />
    <h1>{{ title }}</h1>
    <button v-if="offline" class="primary" @click="reload">重新连接</button
    ><router-link v-else class="button" :to="home()">返回首页</router-link>
  </section>
</template>
