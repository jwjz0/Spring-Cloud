<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, orderApi, payApi, productApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import TraceTimeline from '../../components/TraceTimeline.vue'

type PressureRecord = {
  seq: number
  orderId: string
  createStatus: 'success' | 'fail'
  payStatus: 'success' | 'fail' | 'pending' | 'skip'
  createMs: number
  payMs: number
  totalMs: number
  message: string
}

type PressureSummary = {
  mode: 'serial' | 'concurrent'
  requestCount: number
  concurrency: number
  createSuccess: number
  createFail: number
  paySuccess: number
  payFail: number
  completed: number
  elapsedMs: number
  qps: number
  avgMs: number
  p95Ms: number
  autoPay: boolean
}

type CompareResult = {
  serial: PressureSummary
  concurrent: PressureSummary
  speedup: number
  qpsImprove: number
  avgDrop: number
}

type CompareMetric = {
  key: string
  label: string
  unit: string
  serial: number
  concurrent: number
  better: 'higher' | 'lower'
  serialWidth: string
  concurrentWidth: string
  deltaText: string
  winner: 'serial' | 'concurrent' | 'tie'
}

type TrendRow = {
  concurrency: number
  elapsedMs: number
  qps: number
  avgMs: number
  p95Ms: number
  createSuccess: number
  createFail: number
  paySuccess: number
  payFail: number
  speedupVsBase: number
  qpsImproveVsBase: number
}

type PressurePhase = 'creating' | 'paying' | 'done'

type PressureProgress = {
  mode: 'serial' | 'concurrent'
  requestCount: number
  concurrency: number
  autoPay: boolean
  phase: PressurePhase
  startedAt: number
  elapsedMs: number
  createDone: number
  createSuccess: number
  createFail: number
  payDone: number
  paySuccess: number
  payFail: number
}

type ScenarioCallbacks = {
  onPhaseChange?: (phase: PressurePhase) => void
  onCreateRecord?: (record: PressureRecord, done: number, total: number) => void
  onPayRecord?: (record: PressureRecord, done: number, total: number) => void
}

const router = useRouter()
const route = useRoute()
const products = ref<any[]>([])
const orderTrace = ref<any[]>([])
const showTrace = ref(false)
const createdOrderId = ref('')

const resetLoading = ref(false)
const pressureLoading = ref(false)
const compareLoading = ref(false)
const pressureProductId = ref<number | null>(null)
const pressureRequestCount = ref(20)
const pressureConcurrency = ref(10)
const autoPay = ref(true)
const pressureSummary = ref<PressureSummary | null>(null)
const compareResult = ref<CompareResult | null>(null)
const trendLoading = ref(false)
const trendProgressText = ref('')
const trendConcurrencyText = ref('1,5,10,20')
const trendResults = ref<TrendRow[]>([])
const pressureRecords = ref<PressureRecord[]>([])
const pressureProgress = ref<PressureProgress | null>(null)
const showPressureTrace = ref(false)
const pressureTraceOrderId = ref('')
const pressureTraceSteps = ref<any[]>([])
const pressureTraceLoading = ref(false)
const pressureRecordIndexMap = new Map<number, number>()
const pendingPressureRecordMap = new Map<number, PressureRecord>()
const PRESSURE_FLUSH_INTERVAL_MS = 80
let pressureFlushTimer: ReturnType<typeof setTimeout> | null = null

const trendMaxQps = computed(() => Math.max(1, ...trendResults.value.map((item) => item.qps)))
const trendMaxElapsed = computed(() => Math.max(1, ...trendResults.value.map((item) => item.elapsedMs)))
const toolLocked = computed(
  () => pressureLoading.value || compareLoading.value || trendLoading.value || resetLoading.value
)
const compareMetrics = computed<CompareMetric[]>(() => {
  if (!compareResult.value) return []
  const rows = [
    { key: 'elapsed', label: '总耗时', unit: 'ms', serial: compareResult.value.serial.elapsedMs, concurrent: compareResult.value.concurrent.elapsedMs, better: 'lower' as const },
    { key: 'qps', label: 'QPS', unit: '', serial: compareResult.value.serial.qps, concurrent: compareResult.value.concurrent.qps, better: 'higher' as const },
    { key: 'avg', label: '平均耗时', unit: 'ms', serial: compareResult.value.serial.avgMs, concurrent: compareResult.value.concurrent.avgMs, better: 'lower' as const },
    { key: 'p95', label: 'P95', unit: 'ms', serial: compareResult.value.serial.p95Ms, concurrent: compareResult.value.concurrent.p95Ms, better: 'lower' as const },
  ]

  return rows.map((row) => {
    const max = Math.max(row.serial, row.concurrent, 1)
    const serialWin = row.better === 'lower' ? row.serial < row.concurrent : row.serial > row.concurrent
    const concurrentWin = row.better === 'lower' ? row.concurrent < row.serial : row.concurrent > row.serial
    const diffValue = Math.abs(row.concurrent - row.serial)
    const diffPercent =
      row.serial > 0 ? round((diffValue / row.serial) * 100, 1) : 0
    return {
      ...row,
      serialWidth: metricWidth(row.serial, max),
      concurrentWidth: metricWidth(row.concurrent, max),
      deltaText:
        row.unit === 'ms'
          ? `${diffValue}ms / ${diffPercent}%`
          : `${round(diffValue, 2)} / ${diffPercent}%`,
      winner: concurrentWin ? 'concurrent' : serialWin ? 'serial' : 'tie',
    }
  })
})
const compareConclusion = computed(() => {
  if (!compareResult.value) return ''
  if (compareResult.value.speedup >= 2) {
    return '并发模式已经明显拉开差距，当前场景下吞吐优势比较直观。'
  }
  if (compareResult.value.speedup >= 1.3) {
    return '并发模式有一定优势，但还没有到特别夸张的程度。'
  }
  return '当前差距偏小，通常是因为请求量较少、并发量不高，或者接口本身响应很快。'
})
const pressurePhaseText = computed(() => {
  const phase = pressureProgress.value?.phase
  if (phase === 'creating') return '下单中'
  if (phase === 'paying') return '支付中'
  if (phase === 'done') return '已完成'
  return '未开始'
})
const createProgressPercent = computed(() => {
  const progress = pressureProgress.value
  if (!progress || progress.requestCount <= 0) return 0
  return round(Math.min(100, (progress.createDone / progress.requestCount) * 100), 1)
})
const payProgressBase = computed(() => {
  const progress = pressureProgress.value
  if (!progress || !progress.autoPay) return 0
  return progress.createSuccess
})
const payProgressPercent = computed(() => {
  const progress = pressureProgress.value
  if (!progress || !progress.autoPay) return 0
  const base = payProgressBase.value
  if (base <= 0) {
    return progress.createDone === progress.requestCount ? 100 : 0
  }
  return round(Math.min(100, (progress.payDone / base) * 100), 1)
})
const isTestToolsPage = computed(() => route.path === '/test-tools')

onMounted(async () => {
  await loadProducts()
})

onBeforeUnmount(() => {
  if (pressureFlushTimer) {
    clearTimeout(pressureFlushTimer)
    pressureFlushTimer = null
  }
})

async function loadProducts() {
  const res: any = await productApi.list()
  products.value = res.data || []
  if (!pressureProductId.value && products.value.length > 0) {
    pressureProductId.value = Number(products.value[0].id)
  }
}

async function handleBuy(product: any) {
  if (product.stock <= 0) {
    ElMessage.warning('该商品库存不足，无法购买')
    return
  }
  await ElMessageBox.confirm(
    `确认购买「${product.name}」？价格：¥${product.price}`,
    '确认下单',
    { confirmButtonText: '确认', cancelButtonText: '取消', type: 'info' }
  )

  try {
    const res: any = await orderApi.create({ productId: product.id, quantity: 1 })
    const traceData = res.data
    const order = traceData.data
    createdOrderId.value = order.id

    if (traceData.trace && traceData.trace.length > 0) {
      orderTrace.value = traceData.trace
      showTrace.value = true
    }

    ElMessage.success('下单成功！')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '下单失败')
  }
  await loadProducts()
}

function goToPay() {
  router.push(`/pay/${createdOrderId.value}`)
}

function getErrorMessage(error: any) {
  return error?.response?.data?.message || error?.message || '请求失败'
}

function percentile(values: number[], p: number) {
  if (values.length === 0) return 0
  const idx = Math.min(values.length - 1, Math.ceil(values.length * p) - 1)
  return values[idx] ?? 0
}

function round(value: number, digits = 2) {
  return Number(value.toFixed(digits))
}

async function runInPool<T>(
  total: number,
  limit: number,
  task: (seq: number) => Promise<T>,
  onResolved?: (result: T, seq: number) => void
) {
  const results: T[] = new Array(total)
  let next = 0
  const workerCount = Math.max(1, Math.min(limit, total))
  await Promise.all(
    Array.from({ length: workerCount }, async () => {
      while (true) {
        const idx = next
        next += 1
        if (idx >= total) break
        const seq = idx + 1
        const result = await task(seq)
        results[idx] = result
        onResolved?.(result, seq)
      }
    })
  )
  return results
}

function buildSummary(
  mode: 'serial' | 'concurrent',
  records: PressureRecord[],
  elapsedMs: number,
  requestCount: number,
  concurrency: number,
  autoPayEnabled: boolean
): PressureSummary {
  const createSuccess = records.filter((item) => item.createStatus === 'success').length
  const createFail = requestCount - createSuccess
  const paySuccess = records.filter((item) => item.payStatus === 'success').length
  const payFail = records.filter((item) => item.payStatus === 'fail').length
  const completed = autoPayEnabled ? paySuccess : createSuccess
  const latencyList = records
    .filter((item) => (autoPayEnabled ? item.payStatus === 'success' : item.createStatus === 'success'))
    .map((item) => Number(item.totalMs) || 0)
    .sort((a, b) => a - b)

  const avgMs =
    latencyList.length > 0 ? round(latencyList.reduce((sum, item) => sum + item, 0) / latencyList.length, 1) : 0
  const p95Ms = round(percentile(latencyList, 0.95), 1)
  const qps = elapsedMs > 0 ? round(completed / (elapsedMs / 1000), 2) : 0

  return {
    mode,
    requestCount,
    concurrency,
    createSuccess,
    createFail,
    paySuccess,
    payFail,
    completed,
    elapsedMs,
    qps,
    avgMs,
    p95Ms,
    autoPay: autoPayEnabled,
  }
}

function buildCompare(serial: PressureSummary, concurrent: PressureSummary): CompareResult {
  const speedup = concurrent.elapsedMs > 0 ? round(serial.elapsedMs / concurrent.elapsedMs, 2) : 0
  const qpsImprove = serial.qps > 0 ? round(((concurrent.qps - serial.qps) / serial.qps) * 100, 1) : 0
  const avgDrop = serial.avgMs > 0 ? round(((serial.avgMs - concurrent.avgMs) / serial.avgMs) * 100, 1) : 0
  return {
    serial,
    concurrent,
    speedup,
    qpsImprove,
    avgDrop,
  }
}

async function runScenario(mode: 'serial' | 'concurrent', callbacks?: ScenarioCallbacks) {
  const selectedProductId = Number(pressureProductId.value)
  const requestCount = Number(pressureRequestCount.value)
  const concurrency = mode === 'serial' ? 1 : Number(pressureConcurrency.value)
  const autoPayEnabled = autoPay.value
  const startedAt = Date.now()
  callbacks?.onPhaseChange?.('creating')
  let createDone = 0

  const createRecords = await runInPool<PressureRecord>(
    requestCount,
    concurrency,
    async (seq) => {
      const createStart = Date.now()
      try {
        const res: any = await orderApi.create({ productId: selectedProductId, quantity: 1 })
        const traceData = res.data
        const orderId = traceData?.data?.id ? String(traceData.data.id) : ''
        if (!orderId) {
          return {
            seq,
            orderId: '',
            createStatus: 'fail',
            payStatus: 'skip',
            createMs: Date.now() - createStart,
            payMs: 0,
            totalMs: Date.now() - createStart,
            message: '下单返回为空',
          }
        }
        return {
          seq,
          orderId,
          createStatus: 'success',
          payStatus: autoPayEnabled ? 'pending' : 'skip',
          createMs: Date.now() - createStart,
          payMs: 0,
          totalMs: Date.now() - createStart,
          message: '',
        }
      } catch (error: any) {
        const spent = Date.now() - createStart
        return {
          seq,
          orderId: '',
          createStatus: 'fail',
          payStatus: 'skip',
          createMs: spent,
          payMs: 0,
          totalMs: spent,
          message: getErrorMessage(error),
        }
      }
    },
    (record) => {
      createDone += 1
      callbacks?.onCreateRecord?.(record, createDone, requestCount)
    }
  )

  let finalRecords: PressureRecord[] = [...createRecords]
  if (autoPayEnabled) {
    callbacks?.onPhaseChange?.('paying')
    const successRecords = createRecords.filter((item) => item.createStatus === 'success')
    let payDone = 0
    const paidRecords = await runInPool<PressureRecord>(
      successRecords.length,
      concurrency,
      async (idx) => {
        const target = successRecords[idx - 1]!
        const payStart = Date.now()
        try {
          await payApi.create(target.orderId, 'alipay')
          const payMs = Date.now() - payStart
          return {
            ...target,
            payStatus: 'success',
            payMs,
            totalMs: (target.createMs || 0) + payMs,
          }
        } catch (error: any) {
          const payMs = Date.now() - payStart
          return {
            ...target,
            payStatus: 'fail',
            payMs,
            totalMs: (target.createMs || 0) + payMs,
            message: target.message || getErrorMessage(error),
          }
        }
      },
      (record) => {
        payDone += 1
        callbacks?.onPayRecord?.(record, payDone, successRecords.length)
      }
    )
    const paidMap = new Map<number, PressureRecord>(paidRecords.map((item) => [item.seq, item]))
    finalRecords = createRecords.map((item) => paidMap.get(item.seq) ?? item)
  }

  const elapsedMs = Date.now() - startedAt
  const summary = buildSummary(mode, finalRecords, elapsedMs, requestCount, concurrency, autoPayEnabled)
  callbacks?.onPhaseChange?.('done')
  return { records: finalRecords, summary }
}

function parseTrendConcurrency(raw: string) {
  const tokens = raw
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
  if (tokens.length === 0) return []
  const levels: number[] = []
  for (const token of tokens) {
    const value = Number(token)
    if (!Number.isInteger(value) || value < 1 || value > 200) {
      return []
    }
    levels.push(value)
  }
  return Array.from(new Set(levels)).sort((a, b) => a - b)
}

function metricWidth(value: number, max: number) {
  if (value <= 0 || max <= 0) return '0%'
  const percent = (value / max) * 100
  return `${Math.max(6, Math.min(100, round(percent, 1)))}%`
}

function formatMetricValue(value: number, unit: string) {
  return unit ? `${value}${unit}` : String(value)
}

function resetPressureRecords() {
  if (pressureFlushTimer) {
    clearTimeout(pressureFlushTimer)
    pressureFlushTimer = null
  }
  pressureRecordIndexMap.clear()
  pendingPressureRecordMap.clear()
  pressureRecords.value = []
}

function setPressureRecords(records: PressureRecord[]) {
  resetPressureRecords()
  pressureRecords.value = records
  for (let i = 0; i < records.length; i += 1) {
    pressureRecordIndexMap.set(records[i]!.seq, i)
  }
}

function upsertPressureRecordImmediate(record: PressureRecord) {
  const idx = pressureRecordIndexMap.get(record.seq)
  if (idx !== undefined) {
    pressureRecords.value[idx] = record
    return
  }
  const insertAt = pressureRecords.value.length
  pressureRecords.value.push(record)
  pressureRecordIndexMap.set(record.seq, insertAt)
}

function flushPendingPressureRecords() {
  if (pressureFlushTimer) {
    clearTimeout(pressureFlushTimer)
    pressureFlushTimer = null
  }
  if (pendingPressureRecordMap.size === 0) return
  const updates = Array.from(pendingPressureRecordMap.values())
  pendingPressureRecordMap.clear()
  for (const record of updates) {
    upsertPressureRecordImmediate(record)
  }
}

function schedulePressureFlush() {
  if (pressureFlushTimer) return
  pressureFlushTimer = setTimeout(() => {
    pressureFlushTimer = null
    flushPendingPressureRecords()
  }, PRESSURE_FLUSH_INTERVAL_MS)
}

function queuePressureRecord(record: PressureRecord) {
  pendingPressureRecordMap.set(record.seq, record)
  schedulePressureFlush()
}

function beginPressureProgress(
  mode: 'serial' | 'concurrent',
  requestCount: number,
  concurrency: number,
  autoPayEnabled: boolean
) {
  pressureProgress.value = {
    mode,
    requestCount,
    concurrency,
    autoPay: autoPayEnabled,
    phase: 'creating',
    startedAt: Date.now(),
    elapsedMs: 0,
    createDone: 0,
    createSuccess: 0,
    createFail: 0,
    payDone: 0,
    paySuccess: 0,
    payFail: 0,
  }
}

function updatePressurePhase(phase: PressurePhase) {
  const progress = pressureProgress.value
  if (!progress) return
  progress.phase = phase
  progress.elapsedMs = Date.now() - progress.startedAt
}

function updateCreateProgress(record: PressureRecord, done: number) {
  queuePressureRecord(record)
  const progress = pressureProgress.value
  if (!progress) return
  progress.createDone = done
  if (record.createStatus === 'success') progress.createSuccess += 1
  if (record.createStatus === 'fail') progress.createFail += 1
  progress.elapsedMs = Date.now() - progress.startedAt
}

function updatePayProgress(record: PressureRecord, done: number) {
  queuePressureRecord(record)
  const progress = pressureProgress.value
  if (!progress) return
  progress.payDone = done
  if (record.payStatus === 'success') progress.paySuccess += 1
  if (record.payStatus === 'fail') progress.payFail += 1
  progress.elapsedMs = Date.now() - progress.startedAt
}

function validatePressureParams() {
  if (!pressureProductId.value) {
    ElMessage.warning('请先选择一个商品')
    return false
  }
  const requestCount = Number(pressureRequestCount.value)
  const concurrency = Number(pressureConcurrency.value)
  if (!Number.isInteger(requestCount) || requestCount < 1) {
    ElMessage.warning('请求数必须是大于 0 的整数')
    return false
  }
  if (!Number.isInteger(concurrency) || concurrency < 1) {
    ElMessage.warning('并发量必须是大于 0 的整数')
    return false
  }
  return true
}

async function handleResetEnvironment() {
  await ElMessageBox.confirm(
    '将清空所有订单、支付记录、链路数据，并把商品库存恢复为初始值。是否继续？',
    '测试环境重置',
    { confirmButtonText: '确认重置', cancelButtonText: '取消', type: 'warning' }
  )

  resetLoading.value = true
  try {
    const res: any = await adminApi.resetTestData()
    const data = res.data || {}
    ElMessage.success(
      `重置完成：订单 ${data.clearedOrderCount || 0}，支付 ${data.clearedPayCount || 0}，链路 ${data.clearedTraceCount || 0}`
    )
    resetPressureRecords()
    pressureSummary.value = null
    compareResult.value = null
    pressureProgress.value = null
    trendResults.value = []
    trendProgressText.value = ''
    await loadProducts()
  } finally {
    resetLoading.value = false
  }
}

async function handleConcurrentTest() {
  if (!validatePressureParams()) return
  pressureLoading.value = true
  pressureSummary.value = null
  resetPressureRecords()
  compareResult.value = null
  trendResults.value = []
  trendProgressText.value = ''
  const requestCount = Number(pressureRequestCount.value)
  const concurrency = Number(pressureConcurrency.value)
  const autoPayEnabled = autoPay.value
  beginPressureProgress('concurrent', requestCount, concurrency, autoPayEnabled)
  try {
    const result = await runScenario('concurrent', {
      onPhaseChange: (phase) => updatePressurePhase(phase),
      onCreateRecord: (record, done) => updateCreateProgress(record, done),
      onPayRecord: (record, done) => updatePayProgress(record, done),
    })
    flushPendingPressureRecords()
    if (pressureRecords.value.length === 0) {
      setPressureRecords(result.records)
    }
    pressureSummary.value = result.summary
    if (pressureProgress.value) {
      pressureProgress.value.phase = 'done'
      pressureProgress.value.elapsedMs = result.summary.elapsedMs
    }
    ElMessage.success('并发模拟完成')
    await loadProducts()
  } catch (error) {
    flushPendingPressureRecords()
    if (pressureProgress.value) {
      pressureProgress.value.phase = 'done'
      pressureProgress.value.elapsedMs = Date.now() - pressureProgress.value.startedAt
    }
    throw error
  } finally {
    flushPendingPressureRecords()
    pressureLoading.value = false
  }
}

async function handleCompareTest() {
  if (!validatePressureParams()) return
  await ElMessageBox.confirm(
    '将自动执行：重置环境 → 串行压测 → 再重置 → 并发压测，用于生成可视化对比结果。',
    '串并对比',
    { confirmButtonText: '开始对比', cancelButtonText: '取消', type: 'info' }
  )

  compareLoading.value = true
  pressureLoading.value = true
  pressureProgress.value = null
  try {
    await adminApi.resetTestData()
    const serial = await runScenario('serial')
    await adminApi.resetTestData()
    const concurrent = await runScenario('concurrent')
    setPressureRecords(concurrent.records)
    pressureSummary.value = concurrent.summary
    compareResult.value = buildCompare(serial.summary, concurrent.summary)
    ElMessage.success('串并对比完成')
    await loadProducts()
  } finally {
    compareLoading.value = false
    pressureLoading.value = false
  }
}

async function handleTrendTest() {
  if (!validatePressureParams()) return
  const inputLevels = parseTrendConcurrency(trendConcurrencyText.value)
  if (inputLevels.length === 0) {
    ElMessage.warning('并发组格式错误，请输入如：1,5,10,20（1~200 的整数）')
    return
  }
  if (inputLevels.length > 10) {
    ElMessage.warning('并发组最多 10 组，避免测试时间过长')
    return
  }
  const levels = inputLevels.includes(1) ? inputLevels : [1, ...inputLevels]

  await ElMessageBox.confirm(
    `将按并发 ${levels.join(' / ')} 自动执行多轮测试（每轮前重置环境），用于生成并发趋势图。是否继续？`,
    '并发趋势测试',
    { confirmButtonText: '开始测试', cancelButtonText: '取消', type: 'info' }
  )

  trendLoading.value = true
  pressureLoading.value = true
  trendProgressText.value = ''
  compareResult.value = null
  trendResults.value = []
  pressureProgress.value = null

  const originConcurrency = pressureConcurrency.value
  try {
    const grouped: Array<{ concurrency: number; summary: PressureSummary; records: PressureRecord[] }> = []
    for (let i = 0; i < levels.length; i += 1) {
      const concurrency = levels[i]!
      trendProgressText.value = `执行中：并发 ${concurrency}（第 ${i + 1}/${levels.length} 组）`
      pressureConcurrency.value = concurrency
      await adminApi.resetTestData()
      const mode: 'serial' | 'concurrent' = concurrency === 1 ? 'serial' : 'concurrent'
      const result = await runScenario(mode)
      grouped.push({ concurrency, summary: result.summary, records: result.records })
    }

    if (grouped.length === 0) {
      throw new Error('未生成趋势测试结果')
    }
    const baseSummary = grouped.find((item) => item.concurrency === 1)?.summary || grouped[0]!.summary
    trendResults.value = grouped.map((item) => ({
      concurrency: item.concurrency,
      elapsedMs: item.summary.elapsedMs,
      qps: item.summary.qps,
      avgMs: item.summary.avgMs,
      p95Ms: item.summary.p95Ms,
      createSuccess: item.summary.createSuccess,
      createFail: item.summary.createFail,
      paySuccess: item.summary.paySuccess,
      payFail: item.summary.payFail,
      speedupVsBase: item.summary.elapsedMs > 0 ? round(baseSummary.elapsedMs / item.summary.elapsedMs, 2) : 0,
      qpsImproveVsBase: baseSummary.qps > 0 ? round(((item.summary.qps - baseSummary.qps) / baseSummary.qps) * 100, 1) : 0,
    }))

    const last = grouped[grouped.length - 1]!
    setPressureRecords(last.records)
    pressureSummary.value = last.summary
    trendProgressText.value = `测试完成：共 ${levels.length} 组`
    ElMessage.success(`并发趋势测试完成（${levels.length} 组）`)
    await loadProducts()
  } finally {
    pressureConcurrency.value = originConcurrency
    trendLoading.value = false
    pressureLoading.value = false
  }
}

function statusType(status: string) {
  const map: Record<string, string> = {
    success: 'success',
    fail: 'danger',
    pending: 'warning',
    skip: 'info',
  }
  return map[status] || 'info'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    success: '成功',
    fail: '失败',
    pending: '待处理',
    skip: '跳过',
  }
  return map[status] || '未知'
}

async function handleViewPressureTrace(orderId: string) {
  pressureTraceOrderId.value = orderId
  showPressureTrace.value = true
  pressureTraceLoading.value = true
  try {
    const res: any = await orderApi.trace(orderId)
    pressureTraceSteps.value = res.data || []
    if (pressureTraceSteps.value.length === 0) {
      ElMessage.warning('该订单暂无链路数据')
    }
  } finally {
    pressureTraceLoading.value = false
  }
}
</script>

<template>
  <div>
    <el-card v-if="isTestToolsPage" class="test-panel" shadow="never">
      <template #header>
        <div class="test-panel-header">测试工具（仅本地测试）</div>
      </template>

      <div class="tool-layout">
        <div class="tool-block">
          <div class="tool-block-title">环境控制</div>
          <div class="tool-row">
            <el-button type="danger" plain :loading="resetLoading" :disabled="toolLocked" @click="handleResetEnvironment">
              清空订单并还原库存
            </el-button>
            <span class="tool-tip">会清空所有用户订单、支付记录、调用链路数据</span>
          </div>
        </div>

        <div class="tool-block">
          <div class="tool-block-title">压测参数</div>
          <div class="param-grid">
            <div class="param-item">
              <span class="param-label">测试商品</span>
              <el-select v-model="pressureProductId" placeholder="选择商品" :disabled="toolLocked">
                <el-option
                  v-for="product in products"
                  :key="product.id"
                  :label="`${product.name}（库存:${product.stock}）`"
                  :value="product.id"
                />
              </el-select>
            </div>
            <div class="param-item">
              <span class="param-label">请求总数</span>
              <el-input-number v-model="pressureRequestCount" :min="1" :max="500" :step="1" :disabled="toolLocked" />
            </div>
            <div class="param-item">
              <span class="param-label">并发量</span>
              <el-input-number v-model="pressureConcurrency" :min="1" :max="200" :step="1" :disabled="toolLocked" />
            </div>
            <div class="param-item">
              <span class="param-label">测试模式</span>
              <el-switch v-model="autoPay" active-text="自动支付" inactive-text="仅下单" :disabled="toolLocked" />
            </div>
          </div>
          <div class="tool-tip">说明：并发量=1 即串行。建议先重置环境再执行压测。</div>
        </div>

        <div class="tool-block">
          <div class="tool-block-title">测试操作</div>
          <div class="tool-row">
            <el-button type="primary" :loading="pressureLoading" :disabled="toolLocked" @click="handleConcurrentTest">
              并发压测
            </el-button>
            <el-button type="success" :loading="compareLoading" :disabled="toolLocked" @click="handleCompareTest">
              串并对比
            </el-button>
          </div>
          <div class="tool-row">
            <el-input
              v-model="trendConcurrencyText"
              style="width: 260px"
              placeholder="趋势并发组：如 1,5,10,20"
              clearable
              :disabled="toolLocked"
            />
            <el-button type="warning" :loading="trendLoading" :disabled="toolLocked" @click="handleTrendTest">
              并发趋势
            </el-button>
            <span class="tool-tip">会按每个并发组自动重置并执行一轮，默认含并发 1 作为基线</span>
          </div>
        </div>
      </div>
      <div v-if="trendLoading || trendProgressText" class="tool-tip">{{ trendProgressText }}</div>

      <el-card v-if="pressureProgress" class="live-card" shadow="never">
        <template #header>
          <div class="test-panel-header">实时执行进度</div>
        </template>
        <div class="live-grid">
          <div class="live-item">模式：{{ pressureProgress.mode === 'serial' ? '串行' : '并发' }}</div>
          <div class="live-item">阶段：{{ pressurePhaseText }}</div>
          <div class="live-item">请求总数：{{ pressureProgress.requestCount }}</div>
          <div class="live-item">并发量：{{ pressureProgress.concurrency }}</div>
          <div class="live-item">下单成功/失败：{{ pressureProgress.createSuccess }} / {{ pressureProgress.createFail }}</div>
          <div v-if="pressureProgress.autoPay" class="live-item">
            支付成功/失败：{{ pressureProgress.paySuccess }} / {{ pressureProgress.payFail }}
          </div>
          <div class="live-item">当前耗时：{{ pressureProgress.elapsedMs }}ms</div>
        </div>
        <div class="progress-stack">
          <div>
            <div class="progress-label">下单进度：{{ pressureProgress.createDone }} / {{ pressureProgress.requestCount }}</div>
            <el-progress :percentage="createProgressPercent" :stroke-width="12" />
          </div>
          <div v-if="pressureProgress.autoPay">
            <div class="progress-label">支付进度：{{ pressureProgress.payDone }} / {{ payProgressBase }}</div>
            <el-progress :percentage="payProgressPercent" :stroke-width="12" color="#67c23a" />
          </div>
        </div>
        <div class="tool-tip">表格会实时刷新，每个请求完成后立即显示或更新。</div>
      </el-card>

      <el-card v-if="compareResult" class="compare-card" shadow="never">
        <template #header>
          <div class="test-panel-header">并发收益对比</div>
        </template>
        <div class="compare-hero">
          <div class="compare-badge">{{ compareResult.speedup }}x</div>
          <div class="compare-hero-text">
            <div class="compare-hero-title">串行 vs 并发</div>
            <div class="compare-hero-desc">{{ compareConclusion }}</div>
          </div>
        </div>
        <div class="compare-grid">
          <div class="compare-item">串行耗时：{{ compareResult.serial.elapsedMs }}ms</div>
          <div class="compare-item">并发耗时：{{ compareResult.concurrent.elapsedMs }}ms</div>
          <div class="compare-item">提速倍数：{{ compareResult.speedup }}x</div>
          <div class="compare-item">QPS 提升：{{ compareResult.qpsImprove }}%</div>
          <div class="compare-item">平均时延下降：{{ compareResult.avgDrop }}%</div>
          <div class="compare-item">并发 QPS：{{ compareResult.concurrent.qps }}</div>
          <div class="compare-item">串行 P95：{{ compareResult.serial.p95Ms }}ms</div>
          <div class="compare-item">并发 P95：{{ compareResult.concurrent.p95Ms }}ms</div>
        </div>
        <div class="compare-metrics">
          <div v-for="metric in compareMetrics" :key="metric.key" class="compare-metric-card">
            <div class="compare-metric-head">
              <div class="compare-metric-title">{{ metric.label }}</div>
              <div class="compare-metric-delta">差值：{{ metric.deltaText }}</div>
            </div>
            <div class="compare-bar-line">
              <span class="compare-bar-label">串行</span>
              <div class="compare-bar-track">
                <div
                  class="compare-bar-fill compare-bar-serial"
                  :class="{ winner: metric.winner === 'serial' }"
                  :style="{ width: metric.serialWidth }"
                ></div>
              </div>
              <span class="compare-bar-value">{{ formatMetricValue(metric.serial, metric.unit) }}</span>
            </div>
            <div class="compare-bar-line">
              <span class="compare-bar-label">并发</span>
              <div class="compare-bar-track">
                <div
                  class="compare-bar-fill compare-bar-concurrent"
                  :class="{ winner: metric.winner === 'concurrent' }"
                  :style="{ width: metric.concurrentWidth }"
                ></div>
              </div>
              <span class="compare-bar-value">{{ formatMetricValue(metric.concurrent, metric.unit) }}</span>
            </div>
          </div>
        </div>
        <div class="tool-tip compare-tip">如果差距不明显，建议把请求数调到 50~100，并把并发量调到 10~20 再看对比。</div>
      </el-card>

      <el-card v-if="trendResults.length > 0" class="trend-card" shadow="never">
        <template #header>
          <div class="test-panel-header">并发趋势图（自动多组）</div>
        </template>

        <div class="trend-section">
          <div class="trend-title">QPS 趋势（越高越好）</div>
          <div v-for="item in trendResults" :key="`qps-${item.concurrency}`" class="trend-row">
            <span class="trend-label">C={{ item.concurrency }}</span>
            <div class="trend-bar">
              <div class="trend-fill trend-qps" :style="{ width: metricWidth(item.qps, trendMaxQps) }"></div>
            </div>
            <span class="trend-value">{{ item.qps }}</span>
          </div>
        </div>

        <div class="trend-section">
          <div class="trend-title">总耗时趋势（ms，越低越好）</div>
          <div v-for="item in trendResults" :key="`elapsed-${item.concurrency}`" class="trend-row">
            <span class="trend-label">C={{ item.concurrency }}</span>
            <div class="trend-bar">
              <div
                class="trend-fill trend-elapsed"
                :style="{ width: metricWidth(item.elapsedMs, trendMaxElapsed) }"
              ></div>
            </div>
            <span class="trend-value">{{ item.elapsedMs }}</span>
          </div>
        </div>

        <el-table :data="trendResults" size="small" stripe class="trend-table">
          <el-table-column prop="concurrency" label="并发量" width="90" />
          <el-table-column prop="elapsedMs" label="总耗时(ms)" width="110" />
          <el-table-column prop="qps" label="QPS" width="90" />
          <el-table-column prop="avgMs" label="平均耗时(ms)" width="120" />
          <el-table-column prop="p95Ms" label="P95(ms)" width="100" />
          <el-table-column prop="speedupVsBase" label="相对并发1提速(x)" width="140" />
          <el-table-column prop="qpsImproveVsBase" label="QPS提升(%)" width="120" />
          <el-table-column label="下单成功/失败" min-width="120">
            <template #default="{ row }">{{ row.createSuccess }} / {{ row.createFail }}</template>
          </el-table-column>
          <el-table-column v-if="autoPay" label="支付成功/失败" min-width="120">
            <template #default="{ row }">{{ row.paySuccess }} / {{ row.payFail }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-alert v-if="pressureSummary" type="info" :closable="false" show-icon style="margin-top: 12px">
        <template #title>
          {{ pressureSummary.mode === 'serial' ? '串行模式' : '并发模式' }}：请求 {{ pressureSummary.requestCount }}，
          并发 {{ pressureSummary.concurrency }}，下单成功 {{ pressureSummary.createSuccess }}，下单失败
          {{ pressureSummary.createFail }}
          <span v-if="pressureSummary.autoPay">
            ，支付成功 {{ pressureSummary.paySuccess }}，支付失败 {{ pressureSummary.payFail }}
          </span>
          ，总耗时 {{ pressureSummary.elapsedMs }}ms，QPS {{ pressureSummary.qps }}，平均耗时
          {{ pressureSummary.avgMs }}ms，P95 {{ pressureSummary.p95Ms }}ms
        </template>
      </el-alert>

      <el-empty
        v-if="pressureLoading && pressureRecords.length === 0"
        class="waiting-tip"
        description="压测已启动，等待首个请求返回..."
      />

      <el-table
        v-if="pressureRecords.length > 0"
        :data="pressureRecords"
        row-key="seq"
        size="small"
        stripe
        class="test-table"
        max-height="420"
      >
        <el-table-column prop="seq" label="请求序号" width="90" />
        <el-table-column prop="orderId" label="订单号" min-width="180" />
        <el-table-column label="下单状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.createStatus) as any">{{ statusText(row.createStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="支付状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.payStatus) as any">{{ statusText(row.payStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createMs" label="下单耗时(ms)" width="120" />
        <el-table-column prop="payMs" label="支付耗时(ms)" width="120" />
        <el-table-column prop="totalMs" label="总耗时(ms)" width="110" />
        <el-table-column prop="message" label="错误信息" min-width="160" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.orderId" size="small" @click="handleViewPressureTrace(row.orderId)">
              查看链路
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-row v-if="!isTestToolsPage" :gutter="20">
      <el-col :span="8" v-for="product in products" :key="product.id">
        <el-card shadow="hover" class="product-card">
          <div class="product-icon">{{ product.image }}</div>
          <h3>{{ product.name }}</h3>
          <p class="desc">{{ product.description }}</p>
          <div class="bottom">
            <span class="price">¥{{ product.price.toFixed(2) }}</span>
            <el-button type="primary" size="small" :disabled="product.stock <= 0" @click="handleBuy(product)">
              {{ product.stock <= 0 ? '已售罄' : '立即购买' }}
            </el-button>
          </div>
          <div class="stock">库存：{{ product.stock }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="showTrace" title="下单成功" width="700px" :close-on-click-modal="false">
      <el-alert type="success" :closable="false" show-icon style="margin-bottom: 16px">
        <template #title>
          订单号：{{ createdOrderId }}
        </template>
      </el-alert>
      <TraceTimeline title="下单调用链路（后端技术全览）" :steps="orderTrace" />
      <template #footer>
        <el-button @click="showTrace = false">关闭</el-button>
        <el-button type="primary" @click="goToPay">去支付</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showPressureTrace" title="并发调用链路" width="760px" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        <template #title>订单号：{{ pressureTraceOrderId }}</template>
      </el-alert>
      <el-skeleton :rows="6" animated v-if="pressureTraceLoading" />
      <template v-else>
        <TraceTimeline v-if="pressureTraceSteps.length > 0" title="并发订单调用链路" :steps="pressureTraceSteps" />
        <el-empty v-else description="暂无链路数据" />
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.test-panel {
  margin-bottom: 20px;
}

.test-panel-header {
  font-weight: 600;
}

.tool-layout {
  display: grid;
  gap: 10px;
}

.tool-block {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  background: #fafcff;
}

.tool-block-title {
  margin-bottom: 10px;
  color: #303133;
  font-size: 13px;
  font-weight: 600;
}

.tool-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
}

.tool-tip {
  color: #909399;
  font-size: 12px;
}

.param-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 10px 12px;
  margin-bottom: 8px;
}

.param-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.param-label {
  color: #606266;
  font-size: 12px;
}

.live-card {
  margin-top: 12px;
}

.live-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.live-item {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 8px 10px;
  color: #303133;
  font-size: 13px;
}

.progress-stack {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 8px;
}

.progress-label {
  margin-bottom: 4px;
  color: #606266;
  font-size: 12px;
}

.compare-card {
  margin-top: 12px;
}

.compare-hero {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 12px;
  padding: 14px;
  border-radius: 10px;
  background: linear-gradient(135deg, #f0f9ff, #f4f4ff);
}

.compare-badge {
  min-width: 72px;
  height: 72px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #409eff, #67c23a);
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  box-shadow: 0 10px 24px rgba(64, 158, 255, 0.18);
}

.compare-hero-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.compare-hero-title {
  color: #303133;
  font-size: 16px;
  font-weight: 600;
}

.compare-hero-desc {
  color: #606266;
  font-size: 13px;
  line-height: 1.6;
}

.compare-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.compare-item {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  color: #303133;
}

.compare-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px;
}

.compare-metric-card {
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 12px;
  background: #fff;
}

.compare-metric-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.compare-metric-title {
  color: #303133;
  font-size: 14px;
  font-weight: 600;
}

.compare-metric-delta {
  color: #909399;
  font-size: 12px;
}

.compare-bar-line {
  display: grid;
  grid-template-columns: 36px 1fr 78px;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.compare-bar-label {
  color: #606266;
  font-size: 12px;
}

.compare-bar-track {
  height: 10px;
  background: #ebeef5;
  border-radius: 999px;
  overflow: hidden;
}

.compare-bar-fill {
  height: 100%;
  border-radius: inherit;
  opacity: 0.78;
  transition: width 0.25s ease;
}

.compare-bar-fill.winner {
  opacity: 1;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.66) inset;
}

.compare-bar-serial {
  background: linear-gradient(90deg, #e6a23c, #f3d19e);
}

.compare-bar-concurrent {
  background: linear-gradient(90deg, #67c23a, #95d475);
}

.compare-bar-value {
  color: #303133;
  font-size: 12px;
  text-align: right;
}

.compare-tip {
  margin-top: 10px;
}

.trend-card {
  margin-top: 12px;
}

.trend-section {
  margin-bottom: 14px;
}

.trend-title {
  margin-bottom: 8px;
  color: #606266;
  font-size: 13px;
  font-weight: 600;
}

.trend-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.trend-label {
  width: 44px;
  font-size: 12px;
  color: #909399;
}

.trend-bar {
  flex: 1;
  height: 10px;
  background: #ebeef5;
  border-radius: 999px;
  overflow: hidden;
}

.trend-fill {
  height: 100%;
  border-radius: inherit;
}

.trend-qps {
  background: linear-gradient(90deg, #67c23a, #95d475);
}

.trend-elapsed {
  background: linear-gradient(90deg, #f56c6c, #f89898);
}

.trend-value {
  width: 66px;
  text-align: right;
  font-size: 12px;
  color: #303133;
}

.trend-table {
  margin-top: 10px;
}

.test-table {
  margin-top: 12px;
}

.waiting-tip {
  margin-top: 12px;
}

.product-card {
  margin-bottom: 20px;
  text-align: center;
}

.product-icon {
  font-size: 48px;
  margin: 10px 0;
}

.product-card h3 {
  margin: 8px 0;
}

.desc {
  color: #999;
  font-size: 13px;
  margin-bottom: 12px;
}

.bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.price {
  color: #f56c6c;
  font-size: 20px;
  font-weight: bold;
}

.stock {
  margin-top: 8px;
  color: #ccc;
  font-size: 12px;
}
</style>
