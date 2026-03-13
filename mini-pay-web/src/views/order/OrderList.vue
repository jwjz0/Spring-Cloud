<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { orderApi, payApi } from '../../api'
import { ElMessage } from 'element-plus'
import TraceTimeline from '../../components/TraceTimeline.vue'

const router = useRouter()
const orders = ref<any[]>([])
const traceDialogVisible = ref(false)
const traceLoading = ref(false)
const selectedOrderId = ref('')
const orderTrace = ref<any[]>([])
const refundDialogVisible = ref(false)
const refundingOrder = ref<any>(null)
const refundAmount = ref(0)
const refundReason = ref('用户申请退款')

const statusMap: Record<number, { text: string; key: string }> = {
  0: { text: '待支付', key: 'pending' },
  1: { text: '已支付', key: 'paid' },
  2: { text: '已关闭', key: 'closed' },
  3: { text: '已退款', key: 'refunded' },
  4: { text: '支付中', key: 'paying' },
  5: { text: '支付失败', key: 'payFailed' },
  6: { text: '退款中', key: 'refunding' },
}

onMounted(async () => {
  const res: any = await orderApi.list()
  orders.value = (res.data || []).map((o: any) => ({
    ...o,
    statusKey: statusMap[o.status]?.key || 'info',
    statusText: statusMap[o.status]?.text || '未知',
  }))
})

function statusType(key: string) {
  const map: Record<string, string> = {
    pending: 'warning',
    paid: 'success',
    closed: 'info',
    refunded: 'danger',
    paying: '',
    payFailed: 'danger',
    refunding: 'warning',
  }
  return map[key] || 'info'
}

function handlePay(orderId: string) {
  router.push(`/pay/${orderId}`)
}

async function handleViewTrace(orderId: string) {
  selectedOrderId.value = orderId
  traceDialogVisible.value = true
  traceLoading.value = true
  try {
    const res: any = await orderApi.trace(orderId)
    orderTrace.value = res.data || []
    if (orderTrace.value.length === 0) {
      ElMessage.warning('该订单暂无可展示的调用链数据')
    }
  } finally {
    traceLoading.value = false
  }
}

function handleRefund(order: any) {
  refundingOrder.value = order
  refundAmount.value = order.amount
  refundReason.value = '用户申请退款'
  refundDialogVisible.value = true
}

async function refreshOrders() {
  const res: any = await orderApi.list()
  orders.value = (res.data || []).map((o: any) => ({
    ...o,
    statusKey: statusMap[o.status]?.key || 'info',
    statusText: statusMap[o.status]?.text || '未知',
  }))
}

async function confirmRefund() {
  try {
    await payApi.refund({
      orderId: refundingOrder.value.id,
      refundAmount: refundAmount.value,
      reason: refundReason.value,
    })
    ElMessage.success('退款申请已提交')
    refundDialogVisible.value = false
    await refreshOrders()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '退款失败')
  }
}

async function handleMockCallback(orderId: string, type: 'success' | 'fail') {
  try {
    await payApi.mockPayCallback(orderId, type)
    ElMessage.success(type === 'success' ? '模拟支付成功回调已发送' : '模拟支付失败回调已发送')
    await refreshOrders()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '回调失败')
  }
}

async function handleCancel(orderId: string) {
  try {
    await orderApi.cancel(orderId)
    ElMessage.success('订单已取消')
    await refreshOrders()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '取消失败')
  }
}

async function handleMockRefundCallback(orderId: string, type: 'success' | 'fail') {
  try {
    await payApi.mockRefundCallback(orderId, type)
    ElMessage.success(type === 'success' ? '模拟退款成功回调已发送' : '模拟退款失败回调已发送')
    await refreshOrders()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '回调失败')
  }
}
</script>

<template>
  <div>
    <el-table :data="orders" stripe>
      <el-table-column prop="id" label="订单号" width="180" />
      <el-table-column prop="productName" label="商品" width="150" />
      <el-table-column label="金额" width="120">
        <template #default="{ row }">
          <span style="color: #f56c6c; font-weight: bold">¥{{ row.amount.toFixed(2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.statusKey) as any">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="下单时间" width="180" />
      <el-table-column prop="payTime" label="支付时间" width="180" />
      <el-table-column label="操作" fixed="right" width="280">
        <template #default="{ row }">
          <el-button v-if="row.statusKey === 'pending'" type="primary" size="small" @click="handlePay(row.id)">
            去支付
          </el-button>
          <el-button v-if="row.statusKey === 'pending' || row.statusKey === 'payFailed'" type="danger" size="small" @click="handleCancel(row.id)">
            取消订单
          </el-button>
          <el-button v-if="row.statusKey === 'paying'" size="small" @click="handleMockCallback(row.id, 'success')">
            模拟回调成功
          </el-button>
          <el-button v-if="row.statusKey === 'paying'" size="small" type="danger" @click="handleMockCallback(row.id, 'fail')">
            模拟回调失败
          </el-button>
          <el-button v-if="row.statusKey === 'paid'" type="warning" size="small" @click="handleRefund(row)">
            退款
          </el-button>
          <el-button v-if="row.statusKey === 'refunding'" size="small" @click="handleMockRefundCallback(row.id, 'success')">
            模拟退款成功
          </el-button>
          <el-button v-if="row.statusKey === 'refunding'" size="small" type="danger" @click="handleMockRefundCallback(row.id, 'fail')">
            模拟退款失败
          </el-button>
          <el-button size="small" @click="handleViewTrace(row.id)">查看链路</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="traceDialogVisible"
      title="订单调用链"
      width="760px"
      :close-on-click-modal="false"
    >
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        <template #title>订单号：{{ selectedOrderId }}</template>
      </el-alert>

      <el-skeleton :rows="6" animated v-if="traceLoading" />
      <template v-else>
        <TraceTimeline
          v-if="orderTrace.length > 0"
          title="订单全链路（下单 → 支付）"
          :steps="orderTrace"
        />
        <el-empty v-else description="暂无链路数据" />
      </template>
    </el-dialog>

    <!-- 退款弹窗 -->
    <el-dialog v-model="refundDialogVisible" title="退款申请" width="500px">
      <el-form label-width="80px">
        <el-form-item label="订单号">{{ refundingOrder?.id }}</el-form-item>
        <el-form-item label="商品">{{ refundingOrder?.productName }}</el-form-item>
        <el-form-item label="退款金额">
          <el-input-number v-model="refundAmount" :min="0.01" :max="refundingOrder?.amount || 0" :precision="2" />
        </el-form-item>
        <el-form-item label="退款原因">
          <el-input v-model="refundReason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="refundDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmRefund">确认退款</el-button>
      </template>
    </el-dialog>
  </div>
</template>
