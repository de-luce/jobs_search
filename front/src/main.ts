import { createApp } from 'vue'
import { addCollection } from '@iconify/vue'
import biIcons from '@iconify-json/bi/icons.json'
import App from './App.vue'
import router from './router'
import './styles/globals.css'

addCollection(biIcons)

createApp(App).use(router).mount('#app')
