<script setup>
import { onMounted, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage } from 'element-plus'

const list = ref([])
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, code: '', capacity: 1, status: '空闲' })

const ovenStatusType = (s) => (s === '空闲' ? 'success' : s === '占用' ? 'warning' : 'danger')

async function load() {
  list.value = await api.listOvens()
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
  try { await api.breakOven(row.id); await load(); emitChanged() } catch (e) { ElMessage.error(e.message) }
}
async function doRepair(row) {
  try { await api.repairOven(row.id); await load(); emitChanged() } catch (e) { ElMessage.error(e.message) }
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
      <el-table-column prop="code" label="炉号" width="120" />
      <el-table-column prop="capacity" label="容量(同时烤盘数)" width="160" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><el-tag :type="ovenStatusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" min-width="220">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
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
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.bar h2 { margin: 0; font-size: 18px; }
</style>
