import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const gatewayApiUrl = (
    env.VITE_GATEWAY_API_URL || 'http://localhost:8085/api'
  ).replace(/\/+$/, '')
  const scalaJSDirectory = fileURLToPath(
    new URL(`./target/scalajs-${command === 'serve' ? 'fast' : 'full'}/`, import.meta.url),
  )

  return {
    resolve: {
      alias: [{ find: /^scalajs:/, replacement: scalaJSDirectory }],
    },
    define: {
      __IDENTITY_API_URL__: JSON.stringify(
        env.VITE_IDENTITY_API_URL || `${gatewayApiUrl}/identity`,
      ),
      __CATALOG_API_URL__: JSON.stringify(
        env.VITE_CATALOG_API_URL || `${gatewayApiUrl}/catalog`,
      ),
      __PLAYBACK_API_URL__: JSON.stringify(
        env.VITE_PLAYBACK_API_URL || `${gatewayApiUrl}/playback`,
      ),
    },
  }
})
