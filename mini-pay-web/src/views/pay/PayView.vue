<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { orderApi, payApi } from '../../api'
import { ElMessage } from 'element-plus'
import TraceTimeline from '../../components/TraceTimeline.vue'

const route = useRoute()
const router = useRouter()
const orderId = route.params.orderId as string
const payMethod = ref('alipay')
const paying = ref(false)
const payStatus = ref<'pending' | 'waiting' | 'success' | 'fail'>('pending')
const currentPayRecord = ref<any>(null)
const callbacking = ref(false)
const payTrace = ref<any[]>([])

const orderInfo = ref({
  orderId,
  productName: '',
  amount: 0,
  createTime: '',
})

onMounted(async () => {
  try {
    const res: any = await orderApi.detail(orderId)
    const order = res.data
    if (order) {
      orderInfo.value = {
        orderId: order.id,
        productName: order.productName,
        amount: order.amount,
        createTime: order.createTime,
      }
    }
  } catch {
    // 订单可能还没同步到，用基础信息
  }
})

async function handlePay() {
  paying.value = true
  try {
    const res: any = await payApi.create(orderId, payMethod.value)
    const traceData = res.data
    if (traceData && traceData.trace && traceData.trace.length > 0) {
      payTrace.value = traceData.trace
    }
    if (traceData && traceData.data) {
      currentPayRecord.value = traceData.data
    }
    payStatus.value = 'waiting'
    ElMessage.info('支付单已创建，等待渠道回调')
  } catch {
    payStatus.value = 'fail'
    ElMessage.error('支付单创建失败')
  } finally {
    paying.value = false
  }
}

async function handleMockCallback(type: 'success' | 'fail') {
  callbacking.value = true
  try {
    const res: any = await payApi.mockPayCallback(orderId, type)
    const traceData = res.data
    if (traceData && traceData.trace && traceData.trace.length > 0) {
      payTrace.value = [...payTrace.value, ...traceData.trace]
    }
    payStatus.value = type === 'success' ? 'success' : 'fail'
    if (type === 'success') {
      ElMessage.success('支付回调成功！')
    } else {
      ElMessage.error('支付回调失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '回调处理失败')
  } finally {
    callbacking.value = false
  }
}

function goOrderList() {
  router.push('/order')
}
</script>

<template>
  <div class="pay-page">
    <!-- 待支付 -->
    <el-card v-if="payStatus === 'pending'" class="pay-card">
      <h2>订单支付</h2>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="订单号">{{ orderInfo.orderId }}</el-descriptions-item>
        <el-descriptions-item label="商品">{{ orderInfo.productName }}</el-descriptions-item>
        <el-descriptions-item label="金额">
          <span class="amount">¥{{ orderInfo.amount.toFixed(2) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="下单时间">{{ orderInfo.createTime }}</el-descriptions-item>
      </el-descriptions>

      <div class="pay-methods">
        <h3>选择支付方式</h3>
        <el-radio-group v-model="payMethod">
          <el-radio value="alipay">支付宝</el-radio>
          <el-radio value="wechat">微信支付</el-radio>
          <el-radio value="bank">银行卡</el-radio>
        </el-radio-group>
      </div>

      <el-button type="primary" size="large" :loading="paying"
                 style="width: 100%; margin-top: 20px" @click="handlePay">
        {{ paying ? '支付处理中...' : `确认支付 ¥${orderInfo.amount.toFixed(2)}` }}
      </el-button>
    </el-card>

    <!-- 等待回调 -->
    <template v-else-if="payStatus === 'waiting'">
      <el-result icon="info" title="支付单已创建" sub-title="正在等待渠道回调，请点击下方按钮模拟回调结果">
        <template #extra>
          <el-button type="success" :loading="callbacking" @click="handleMockCallback('success')">
            模拟回调成功
          </el-button>
          <el-button type="danger" :loading="callbacking" @click="handleMockCallback('fail')">
            模拟回调失败
          </el-button>
        </template>
      </el-result>
      <TraceTimeline v-if="payTrace.length > 0" title="支付创建调用链路" :steps="payTrace" />
    </template>

    <!-- 支付成功 -->
    <template v-else-if="payStatus === 'success'">
      <el-result icon="success" title="支付成功"
                 sub-title="您的订单已支付完成，系统正在处理中">
        <template #extra>
          <el-button type="primary" @click="goOrderList">查看订单</el-button>
          <el-button @click="router.push('/product')">继续购物</el-button>
        </template>
      </el-result>

      <!-- 支付调用链时间线 -->
      <TraceTimeline v-if="payTrace.length > 0" title="支付调用链路（后端技术全览）" :steps="payTrace" />
    </template>

    <!-- 支付失败 -->
    <el-result v-else icon="error" title="支付失败" sub-title="请稍后重试或联系客服">
      <template #extra>
        <el-button type="primary" @click="payStatus = 'pending'">重新支付</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.pay-page {
  max-width: 700px;
  margin: 0 auto;
}

.pay-card h2 {
  margin-bottom: 20px;
  text-align: center;
}

.amount {
  color: #f56c6c;
  font-size: 24px;
  font-weight: bold;
}

.pay-methods {
  margin-top: 20px;
}

.pay-methods h3 {
  margin-bottom: 10px;
  font-size: 14px;
  color: #666;
}
</style>
