<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import TakeoverDialog from '../components/TakeoverDialog.vue'

const list = ref([])
const orders = ref([])
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, code: '', capacity: 1, status: '空闲' })
const takeoverRef = ref(null)

const ovenStatusType = (s) => (s === '空闲' ? 'success' : s === '占用' ? 'warning' : 'danger')

// 每炉的在炉工单数（待烤 / 烘焙中），用于详情入口与故障提示
const activeByOven = computed(() => {
  const map = {}
  for (const o of orders.value) {
    if (o.status === '待烤' || o.status === '烘焙中') {
      const m = map[o.ovenId] || (map[o.ovenId] = { '待烤': 0, '烘焙中': 0 })
      m[o.status]++
    }
  }
  return map
})

async function load() {
  const [ov, od] = await Promise.all([api.listOvens(), api.listBakeOrders()])
  list.value = ov
  orders.value = od
}

function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, code: '', capacity: 1, status: '空闲' })
  dialog.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, code: row.code, capacity: row.capacity, status: row.status })
  dialog.value = true
}

async function submit() {
  try {
    if (editing.value) {
      await api.updateOven(form.id, { code: form.code, capacity: form.capacity, status: form.status })
    } else {
      await api.createOven({ code: form.code, capacity: form.capacity, status: form.status })
    }
    dialog.value = false
    await load()
    emitChanged()
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doBreak(row) {
  try {
    await api.breakOven(row.id)
    await load()
    emitChanged()
    const active = activeByOven.value[row.id]
    const n = active ? active['待烤'] + active['烘焙中'] : 0
    if (n > 0) {
      // 故障后引导接管：该炉还有在炉工单
      ElMessageBox.confirm(
        `烤箱 ${row.code} 已置为故障，炉上还有 ${n} 张在炉工单（待烤 ${active['待烤']} / 烘焙中 ${active['烘焙中']}）。是否立即故障接管，整批迁移到替代烤箱？`,
        '烤箱已故障',
        { type: 'warning', confirmButtonText: '立即接管', cancelButtonText: '稍后处理' }
      ).then(() => openTakeover(row)).catch(() => {})
    } else {
      ElMessage.success('已置为故障')
    }
  } catch (e) {
    ElMessage.error(e.message)
  }
}
async function doRepair(row) {
  try { await api.repairOven(row.id); await load(); emitChanged(); ElMessage.success('已修复为空闲') } catch (e) { ElMessage.error(e.message) }
}

function openTakeover(row) {
  takeoverRef.value.open(row.id)
}

async function onTakeoverDone() {
  await load()
  emitChanged()
}

const emit = defineEmits(['changed'])
function emitChanged() { emit('changed') }

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <h2>烤箱设备</h2>
      <el-button type="primary" @click="openCreate">新增烤箱</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="code" label="炉号" width="110" />
      <el-table-column prop="capacity" label="容量(同时烤盘数)" width="150" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="ovenStatusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="在炉工单" width="170">
        <template #default="{ row }">
          <template v-if="activeByOven[row.id]">
            <el-tag size="small" type="info" effect="plain">待烤 {{ activeByOven[row.id]['待烤'] }}</el-tag>
            <el-tag size="small" type="warning" effect="plain" class="ml">烘焙中 {{ activeByOven[row.id]['烘焙中'] }}</el-tag>
          </template>
          <span v-else class="none">无</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="300">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            size="small"
            :type="row.status === '故障' ? 'danger' : 'primary'"
            :plain="row.status !== '故障'"
            @click="openTakeover(row)"
          >{{ row.status === '故障' ? '故障接管' : '详情/接管' }}</el-button>
          <el-button size="small" type="warning" v-if="row.status !== '故障'" @click="doBreak(row)">故障</el-button>
          <el-button size="small" type="success" v-if="row.status === '故障'" @click="doRepair(row)">修复</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editing ? '编辑烤箱' : '新增烤箱'" width="420px">
      <el-form label-width="110px">
        <el-form-item label="炉号">
          <el-input v-model="form.code" placeholder="如 OV-05" />
        </el-form-item>
        <el-form-item label="容量(烤盘数)">
          <el-input-number v-model="form.capacity" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="空闲" value="空闲" />
            <el-option label="占用" value="占用" />
            <el-option label="故障" value="故障" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <TakeoverDialog ref="takeoverRef" @done="onTakeoverDone" />
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.bar h2 { margin: 0; font-size: 18px; }
.ml { margin-left: 6px; }
.none { color: var(--muted); font-size: 13px; }
</style>
