import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

const sourceDir = path.join(__dirname, '..', 'dist')
const targetDir = path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'dist')

console.log('开始复制前端构建文件...')
console.log('源目录:', sourceDir)
console.log('目标目录:', targetDir)

function deleteFolderRecursive(dirPath) {
  if (fs.existsSync(dirPath)) {
    fs.readdirSync(dirPath).forEach((file) => {
      const curPath = path.join(dirPath, file)
      if (fs.lstatSync(curPath).isDirectory()) {
        deleteFolderRecursive(curPath)
      } else {
        fs.unlinkSync(curPath)
      }
    })
    fs.rmdirSync(dirPath)
  }
}

function copyFolderRecursive(source, target) {
  if (!fs.existsSync(target)) {
    fs.mkdirSync(target, { recursive: true })
  }
  fs.readdirSync(source).forEach((file) => {
    const sourcePath = path.join(source, file)
    const targetPath = path.join(target, file)
    if (fs.lstatSync(sourcePath).isDirectory()) {
      copyFolderRecursive(sourcePath, targetPath)
    } else {
      fs.copyFileSync(sourcePath, targetPath)
    }
  })
}

try {
  if (!fs.existsSync(sourceDir)) {
    console.error('错误: dist 目录不存在，请先运行 npm run build')
    process.exit(1)
  }
  if (fs.existsSync(targetDir)) {
    console.log('删除旧的目标目录...')
    deleteFolderRecursive(targetDir)
  }
  console.log('复制文件...')
  copyFolderRecursive(sourceDir, targetDir)
  console.log('✅ 构建文件复制成功!')
  console.log(`文件已复制到: ${targetDir}`)
} catch (error) {
  console.error('❌ 复制失败:', error.message)
  process.exit(1)
}
