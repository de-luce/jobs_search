import { spawn } from 'child_process'
import path from 'path'
import { fileURLToPath } from 'url'
import { createRequire } from 'module'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const require = createRequire(import.meta.url)

const configPath = path.join(__dirname, 'server.config.cjs')
delete require.cache[require.resolve(configPath)]
const config = require(configPath)

const port = config.port || 6866
const hostname = config.development?.hostname || '127.0.0.1'

const vite = spawn('npx', ['vite', '--host', hostname, '--port', String(port)], {
  stdio: 'inherit',
  shell: true,
  cwd: __dirname,
})

vite.on('error', (error) => {
  console.error('启动失败:', error)
  process.exit(1)
})

vite.on('close', (code) => {
  process.exit(code ?? 0)
})
