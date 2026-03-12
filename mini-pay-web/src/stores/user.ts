import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUserStore = defineStore('user', () => {
  const username = ref('')
  const token = ref(localStorage.getItem('token') || '')

  function setLogin(name: string, jwt: string) {
    username.value = name
    token.value = jwt
    localStorage.setItem('token', jwt)
    localStorage.setItem('username', name)
  }

  function logout() {
    username.value = ''
    token.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('username')
  }

  function init() {
    username.value = localStorage.getItem('username') || ''
    token.value = localStorage.getItem('token') || ''
  }

  return { username, token, setLogin, logout, init }
})
