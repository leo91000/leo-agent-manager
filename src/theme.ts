import { ref } from 'vue'

export type ThemePreference = 'system' | 'light' | 'dark'
declare global {
  interface Window {
    leoTheme: {
      getPreference: () => ThemePreference
      setPreference: (preference: ThemePreference) => void
      subscribe: (callback: (preference: ThemePreference) => void) => () => void
    }
  }
}
export const themePreference = ref<ThemePreference>(window.leoTheme.getPreference())
window.leoTheme.subscribe(value => themePreference.value = value)
export function setThemePreference(value: ThemePreference) {
  window.leoTheme.setPreference(value)
}
