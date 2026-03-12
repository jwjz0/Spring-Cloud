<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api'

const stats = ref({
  totalOrders: 0,
  todayOrders: 0,
  totalAmount: 0,
  todayAmount: 0,
  totalUsers: 0,
  paySuccessRate: 0,
})

// 暂时保留 Mock 交易记录（后续可扩展后端接口）
const recentTrades = ref<any[]>([])

onMounted(async () => {
  try {
    const res: any = await adminApi.dashboard()
    if (res.data) {
      Object.assign(stats.value, res.data)
    }
  } catch {
    // 接口失败时保持默认值
  }
})

function statusType(status: string) {
  const map: Record<string, string> = { '成功': 'success', '处理中': 'warning', '退款': 'danger', '失败': 'danger' }
  return map[status] || 'info'
}
</script>

<template>
  <div>
    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stat-row">
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="今日订单" :value="stats.todayOrders">
            <template #suffix><span style="font-size: 14px"> 笔</span></template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="今日交易额" :value="stats.todayAmount" :precision="2">
            <template #prefix><span style="font-size: 14px">¥</span></template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="累计订单" :value="stats.totalOrders">
            <template #suffix><span style="font-size: 14px"> 笔</span></template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="支付成功率" :value="stats.paySuccessRate" :precision="1">
            <template #suffix><span style="font-size: 14px">%</span></template>
          </el-statistic>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近交易 -->
    <el-card style="margin-top: 20px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>最近交易记录</span>
          <el-button size="small" type="primary" text>查看全部</el-button>
        </div>
      </template>
      <el-table :data="recentTrades" stripe>
        <el-table-column prop="id" label="支付单号" width="180" />
        <el-table-column prop="orderId" label="订单号" width="180" />
        <el-table-column label="金额" width="120">
          <template #default="{ row }">¥{{ row.amount.toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="channel" label="支付渠道" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status) as any" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="time" label="时间" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.stat-row {
  margin-bottom: 10px;
}
</style>
