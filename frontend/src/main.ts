import { createApp, h } from 'vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import { router } from './router'
import './style.css'

createApp({
  render: () => h(ElConfigProvider, { locale: zhCn }, () => h(App)),
}).use(router).mount('#app')
