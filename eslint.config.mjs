import antfu from '@antfu/eslint-config'

export default antfu({
  vue: true,
  typescript: true,
  ignores: ['docs/screenshots/**', '.data/**', 'playwright-report/**', 'test-results/**'],
})
