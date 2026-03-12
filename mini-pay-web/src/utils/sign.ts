import CryptoJS from 'crypto-js'

const SIGN_SECRET = 'MiniPayCallbackSecret2026'

export function signParams(params: Record<string, string | number | undefined>): string {
  const sorted = Object.keys(params)
    .filter(k => k !== 'sign' && params[k] !== undefined && params[k] !== null)
    .sort()
  const canonical = sorted.map(k => `${k}=${params[k]}`).join('&')
  return CryptoJS.HmacSHA256(canonical, SIGN_SECRET).toString(CryptoJS.enc.Hex)
}
