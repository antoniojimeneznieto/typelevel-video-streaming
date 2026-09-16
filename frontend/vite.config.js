import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const scalaJSDirectory = fileURLToPath(
    new URL(`./target/scalajs-${command === 'serve' ? 'fast' : 'full'}/`, import.meta.url),
  )

  return {
    resolve: {
      alias: [{ find: /^scalajs:/, replacement: scalaJSDirectory }],
    },
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
