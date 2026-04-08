import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@digit-mcp/data-provider': '/opt/egov/DIGIT-MCP/packages/data-provider/dist/index.js',
    },
  },
  server: {
    allowedHosts: ['crs-mockup.egov.theflywheel.in'],
    proxy: {
      '/api/agent': {
        target: 'http://localhost:4100',
        changeOrigin: true,
      },
    },
  },
})
