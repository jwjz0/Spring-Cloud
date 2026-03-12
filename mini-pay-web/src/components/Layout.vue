<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
userStore.init()

const menuItems = [
  { path: '/product', title: '商品列表', icon: 'Goods' },
  { path: '/test-tools', title: '测试工具', icon: 'DataAnalysis' },
  { path: '/order', title: '我的订单', icon: 'List' },
  { path: '/admin', title: '管理后台', icon: 'Setting' },
]

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px">
      <div class="logo">
        <el-icon><CreditCard /></el-icon>
        <span>Mini Pay</span>
      </div>
      <el-menu
        :default-active="route.path"
        router
        background-color="#001529"
        text-color="#ffffffa6"
        active-text-color="#409eff"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <span class="page-title">{{ (route.meta.title as string) || 'Mini Pay' }}</span>
        <div class="user-info">
          <el-icon><User /></el-icon>
          <span>{{ userStore.username || '用户' }}</span>
          <el-button type="danger" text size="small" @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.el-aside {
  background-color: #001529;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 20px;
  font-weight: bold;
  border-bottom: 1px solid #ffffff1a;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eee;
  background: #fff;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
