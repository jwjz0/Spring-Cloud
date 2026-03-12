<script setup lang="ts">
interface TraceStep {
  service: string
  action: string
  tech: string
  detail: string
  status: string // success | warn | fail
  timestamp: number | string
  duration: number | string
}

defineProps<{
  title: string
  steps: TraceStep[]
}>()

function statusColor(status: string) {
  if (status === 'success') return '#67c23a'
  if (status === 'warn') return '#e6a23c'
  return '#f56c6c'
}

function serviceColor(service: string) {
  if (service.includes('gateway')) return '#409eff'
  if (service.includes('order')) return '#67c23a'
  if (service.includes('product')) return '#e6a23c'
  if (service.includes('pay')) return '#f56c6c'
  if (service.includes('RocketMQ')) return '#909399'
  if (service.includes('Kafka')) return '#b37feb'
  return '#409eff'
}

function formatTimestamp(value: number | string) {
  const ts = Number(value)
  if (!Number.isFinite(ts) || ts <= 0) return '-'
  const date = new Date(ts)
  return `${date.toLocaleDateString('zh-CN')} ${date.toLocaleTimeString('zh-CN', { hour12: false })}.${String(
    date.getMilliseconds()
  ).padStart(3, '0')}`
}

function durationValue(value: number | string) {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
</script>

<template>
  <el-card class="trace-card" shadow="hover">
    <template #header>
      <div class="trace-header">
        <el-icon style="margin-right: 6px"><Connection /></el-icon>
        <span>{{ title }}</span>
        <el-tag size="small" type="info" style="margin-left: 8px">{{ steps.length }} 步</el-tag>
      </div>
    </template>

    <el-timeline>
      <el-timeline-item
        v-for="(step, index) in steps"
        :key="index"
        :color="statusColor(step.status)"
        :hollow="step.status === 'warn'"
        size="large"
      >
        <div class="step-content">
          <div class="step-header">
            <el-tag
              :color="serviceColor(step.service)"
              effect="dark"
              size="small"
              style="border: none; margin-right: 8px"
            >{{ step.service }}</el-tag>
            <span class="step-action">{{ step.action }}</span>
            <el-tag size="small" effect="plain">#{{ index + 1 }}</el-tag>
            <el-tag size="small" :type="step.status === 'success' ? 'success' : step.status === 'warn' ? 'warning' : 'danger'">
              {{ step.status }}
            </el-tag>
            <el-tag v-if="durationValue(step.duration) > 0" size="small" type="info" style="margin-left: 8px">
              {{ durationValue(step.duration) }}ms
            </el-tag>
          </div>
          <div class="step-time">{{ formatTimestamp(step.timestamp) }}</div>
          <div class="step-tech">
            <el-tag size="small" effect="plain" round>{{ step.tech }}</el-tag>
          </div>
          <div class="step-detail">{{ step.detail }}</div>
        </div>
      </el-timeline-item>
    </el-timeline>
  </el-card>
</template>

<style scoped>
.trace-card {
  margin-top: 20px;
  background: linear-gradient(135deg, #f5f7fa 0%, #ffffff 100%);
}

.trace-header {
  display: flex;
  align-items: center;
  font-size: 16px;
  font-weight: 600;
}

.step-content {
  padding: 4px 0;
}

.step-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.step-action {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.step-tech {
  margin-top: 6px;
}

.step-time {
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
}

.step-detail {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  padding: 6px 10px;
  background: #f8f9fa;
  border-radius: 4px;
  border-left: 3px solid #dcdfe6;
}
</style>
