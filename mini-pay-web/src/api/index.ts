import request from './request'
import { signParams } from '../utils/sign'

// ========== 用户服务 ==========
export const userApi = {
  login: (data: { username: string; password: string }) =>
    request.post('/user/login', data),
  register: (data: { username: string; password: string }) =>
    request.post('/user/register', data),
  getInfo: () => request.get('/user/info'),
}

// ========== 商品服务 ==========
export const productApi = {
  list: () => request.get('/product/list'),
  detail: (id: number) => request.get(`/product/${id}`),
}

// ========== 订单服务 ==========
export const orderApi = {
  create: (data: { productId: number; quantity: number }) =>
    request.post('/order/create', data),
  list: () => request.get('/order/list'),
  detail: (id: string) => request.get(`/order/${id}`),
  trace: (id: string) => request.get(`/order/trace/${id}`),
}

// ========== 支付服务 ==========
export const payApi = {
  create: (orderId: string, channel: string = 'alipay') =>
    request.post('/pay/create', { orderId, channel }),
  query: (payId: string) => request.get(`/pay/query/${payId}`),
  queryByOrder: (orderId: string) => request.get(`/pay/query-by-order/${orderId}`),

  // 模拟支付回调
  mockPayCallback: async (orderId: string, type: 'success' | 'fail') => {
    const orderRes: any = await request.get(`/pay/query-by-order/${orderId}`)
    const payRecord = orderRes.data
    const outTradeNo = payRecord?.outTradeNo || ''

    const nonce = Math.random().toString(36).substring(2, 15)
    const callbackTimestamp = Date.now()
    const channelTxnNo = 'CH' + callbackTimestamp + Math.floor(Math.random() * 10000)
    const payStatus = type === 'success' ? 'SUCCESS' : 'FAIL'
    const paidAmount = String(payRecord?.amount || '0')

    const params: Record<string, any> = {
      outTradeNo,
      channelTxnNo,
      payStatus,
      paidAmount,
      callbackTimestamp,
      nonce,
    }
    const sign = signParams(params)

    return request.post('/pay/callback/mock', {
      outTradeNo,
      channelTxnNo,
      payStatus,
      paidAmount: payRecord?.amount,
      failReason: type === 'fail' ? '渠道返回支付失败' : null,
      callbackTimestamp,
      nonce,
      rawBody: JSON.stringify(params),
      sign,
    })
  },

  // 退款申请
  refund: (data: { orderId: string; refundAmount: number; reason: string }) =>
    request.post('/pay/refund', data),

  // 查询退款记录
  refundList: (orderId: string) => request.get(`/pay/refund/list/${orderId}`),

  // 模拟退款回调
  mockRefundCallback: async (orderId: string, type: 'success' | 'fail') => {
    const refundRes: any = await request.get(`/pay/refund/list/${orderId}`)
    const refunds = refundRes.data || []
    const refund = refunds.find((r: any) => r.status === 0) || refunds[0]
    if (!refund) throw new Error('无退款记录')

    const nonce = Math.random().toString(36).substring(2, 15)
    const callbackTimestamp = Date.now()
    const channelRefundNo = 'CHREF' + callbackTimestamp + Math.floor(Math.random() * 10000)
    const refundStatus = type === 'success' ? 'SUCCESS' : 'FAIL'

    const params: Record<string, any> = {
      refundNo: refund.refundNo,
      channelRefundNo,
      refundStatus,
      refundAmount: String(refund.amount),
      callbackTimestamp,
      nonce,
    }
    const sign = signParams(params)

    return request.post('/pay/refund/callback/mock', {
      refundNo: refund.refundNo,
      channelRefundNo,
      refundStatus,
      refundAmount: refund.amount,
      failReason: type === 'fail' ? '渠道返回退款失败' : null,
      callbackTimestamp,
      nonce,
      rawBody: JSON.stringify(params),
      sign,
    })
  },
}

// ========== 管理后台 ==========
export const adminApi = {
  dashboard: () => request.get('/admin/dashboard'),
  orderStats: () => request.get('/admin/order-stats'),
  resetTestData: () => request.post('/admin/reset-test-data'),
}
