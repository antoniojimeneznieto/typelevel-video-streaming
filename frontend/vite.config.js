import { defineConfig, loadEnv } from 'vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [scalaJSPlugin({ cwd: '..', projectID: 'frontend' })],
    define: {
      __IDENTITY_API_URL__: JSON.stringify(
        env.VITE_IDENTITY_API_URL || 'http://localhost:8081',
      ),
      __CATALOG_API_URL__: JSON.stringify(
        env.VITE_CATALOG_API_URL || 'http://localhost:8082',
      ),
      __PLAYBACK_API_URL__: JSON.stringify(
        env.VITE_PLAYBACK_API_URL || 'http://localhost:8083',
      ),
    },
  }
})
