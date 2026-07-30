import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem('demo_token')
  if (token) {
    config.headers.set('X-Demo-Token', token)
  }
  return config
})

request.interceptors.response.use(
  (resp) => resp.data,
  (error: AxiosError<{ message?: string }>) => {
    const status = error.response?.status
    const msg = error.response?.data?.message || error.message
    if (status === 401) {
      ElMessage.error('未授权，请检查测试 token')
    } else if (status === 429) {
      ElMessage.warning('请求过于频繁，稍后再试')
    } else if (status === 403) {
      ElMessage.warning('演示环境已禁用该操作')
    } else if (status && status >= 500) {
      ElMessage.error(`服务异常 (${status})：${msg}`)
    } else {
      ElMessage.error(msg || '请求失败')
    }
    return Promise.reject(error)
  }
)

export default request
