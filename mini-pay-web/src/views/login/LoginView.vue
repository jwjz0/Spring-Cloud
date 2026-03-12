<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { userApi } from '../../api'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const isRegister = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' },
             { min: 6, message: '密码至少6位', trigger: 'blur' }],
}

async function handleSubmit() {
  await formRef.value?.validate()
  loading.value = true
  try {
    if (isRegister.value) {
      await userApi.register(form)
      ElMessage.success('注册成功，请登录')
      isRegister.value = false
    } else {
      const res: any = await userApi.login(form)
      userStore.setLogin(res.data.username, res.data.token)
      ElMessage.success('登录成功')
      router.push('/')
    }
  } catch {
    ElMessage.error('操作失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <el-icon :size="40" color="#409eff"><CreditCard /></el-icon>
        <h1>Mini Pay</h1>
        <p>微服务支付系统</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="0" size="large">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码"
                    :prefix-icon="Lock" show-password @keyup.enter="handleSubmit" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" style="width: 100%" @click="handleSubmit">
            {{ isRegister ? '注 册' : '登 录' }}
          </el-button>
        </el-form-item>
      </el-form>

      <div class="switch-mode">
        <el-button link type="primary" @click="isRegister = !isRegister">
          {{ isRegister ? '已有账号？去登录' : '没有账号？去注册' }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 400px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.login-header {
  text-align: center;
  margin-bottom: 30px;
}

.login-header h1 {
  margin: 10px 0 4px;
  color: #333;
}

.login-header p {
  color: #999;
  font-size: 14px;
}

.switch-mode {
  text-align: center;
}
</style>
