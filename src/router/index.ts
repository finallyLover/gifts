// 路由表：主布局嵌套各业务页面
import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  // Tauri 环境使用 hash 路由
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      component: () => import('@/layout/MainLayout.vue'),
      children: [
        { path: '', redirect: '/archives' },
        // 模块1：礼簿档案列表（首页）
        {
          path: 'archives',
          name: 'archives',
          component: () => import('@/views/archive/ArchiveList.vue')
        },
        // 模块2：图片录入校对页
        {
          path: 'entry/:archiveId/:pageId?',
          name: 'entry',
          component: () => import('@/views/entry/EntryView.vue'),
          props: true
        },
        // 模块3：人情档案
        {
          path: 'relations',
          name: 'relations',
          component: () => import('@/views/relation/PersonList.vue')
        },
        // 模块4：检索
        {
          path: 'search',
          name: 'search',
          component: () => import('@/views/search/SearchView.vue')
        },
        // 模块5：统计报表
        {
          path: 'reports',
          name: 'reports',
          component: () => import('@/views/report/ReportView.vue')
        },
        // 模块7：设置/备份
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/settings/SettingsView.vue')
        }
      ]
    }
  ]
})

export default router
