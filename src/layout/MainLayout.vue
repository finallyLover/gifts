<template>
  <!-- 主布局：左侧固定导航 + 顶部标题栏 + 内容区 -->
  <el-container class="layout-root">
    <el-aside width="176px" class="layout-aside">
      <div class="logo">
        <el-icon size="20"><Notebook /></el-icon>
        <span>电子礼簿</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="layout-menu"
        router
      >
        <el-menu-item index="/archives">
          <el-icon><Files /></el-icon>
          <span>礼簿档案</span>
        </el-menu-item>
        <el-menu-item index="/relations">
          <el-icon><User /></el-icon>
          <span>人情档案</span>
        </el-menu-item>
        <el-menu-item index="/search">
          <el-icon><Search /></el-icon>
          <span>检索查询</span>
        </el-menu-item>
        <el-menu-item index="/reports">
          <el-icon><DataAnalysis /></el-icon>
          <span>统计报表</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <span>设置备份</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <span class="header-title">{{ currentTitle }}</span>
      </el-header>
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const activeMenu = computed(() => {
  // 录入页时菜单不高亮任何项（仍保持在档案语境）
  if (route.path.startsWith('/entry')) return '/archives'
  return route.path
})

const titleMap: Record<string, string> = {
  '/archives': '礼簿档案',
  '/relations': '人情档案',
  '/search': '检索查询',
  '/reports': '统计报表',
  '/settings': '设置与备份'
}
const currentTitle = computed(() => titleMap[activeMenu.value] ?? '电子礼簿')
</script>

<style scoped>
.layout-root {
  height: 100%;
}
.layout-aside {
  background: #2b2f38;
  display: flex;
  flex-direction: column;
}
.logo {
  height: 52px;
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  color: #f5d76e;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 2px;
}
.layout-menu {
  border-right: none;
  background: transparent;
}
.layout-menu :deep(.el-menu-item) {
  color: #c8ccd4;
}
.layout-menu :deep(.el-menu-item.is-active) {
  background: #3a3f4b;
  color: #f5d76e;
}
.layout-menu :deep(.el-menu-item:hover) {
  background: #343944;
}
.layout-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  height: 48px;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
}
.layout-main {
  padding: 0;
  background: #f0f2f5;
  /* 纵向可滚动：设置页等长页面需完整显示，横向仍裁掉避免出现横向滚动条 */
  overflow-x: hidden;
  overflow-y: auto;
}
</style>
