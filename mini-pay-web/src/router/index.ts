import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/login/LoginView.vue'),
    },
    {
      path: '/',
      name: 'Layout',
      component: () => import('../components/Layout.vue'),
      redirect: '/product',
      children: [
        {
          path: 'product',
          name: 'Product',
          component: () => import('../views/product/ProductList.vue'),
          meta: { title: '商品列表', icon: 'Goods' },
        },
        {
          path: 'test-tools',
          name: 'TestTools',
          component: () => import('../views/product/ProductList.vue'),
          meta: { title: '测试工具', icon: 'DataAnalysis' },
        },
        {
          path: 'order',
          name: 'Order',
          component: () => import('../views/order/OrderList.vue'),
          meta: { title: '我的订单', icon: 'List' },
        },
        {
          path: 'pay/:orderId',
          name: 'Pay',
          component: () => import('../views/pay/PayView.vue'),
          meta: { title: '订单支付', icon: 'CreditCard' },
        },
        {
          path: 'admin',
          name: 'Admin',
          component: () => import('../views/admin/AdminDashboard.vue'),
          meta: { title: '管理后台', icon: 'Setting' },
        },
      ],
    },
  ],
})

// 路由守卫 - 模拟登录检查
router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
