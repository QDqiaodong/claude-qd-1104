<script setup>
import { computed, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

// ------------------------------------------------------------------
// 故障接管对话框：查看源烤箱在炉工单（待烤/烘焙中），为每张工单选择替代烤箱，
// 预览迁移方案并确认提交。待烤=低风险（仍为待烤）；烘焙中=高风险（保留原开始时间）。
// 父组件通过 ref 调用 open(ovenId) 打开；迁移完成后 emit('done') 让父组件刷新。
// ------------------------------------------------------------------

const visible = ref(false)
const loading = ref(false)
const submitting = ref(false)
const oven = ref(null)
const orders = ref([])
const ovens = ref([])
const recipes = ref([])
const selections = reactive({}) // orderId -> targetOvenId | null（null = 本批不迁移）

const emit = defineEmits(['done'])

const recipeMap = computed(() => Object.fromEntries(recipes.value.map((r) => [r.id, r])))

const ovenStatusType = (s) => (s === '空闲' ? 'success' : s === '占用' ? 'warning' : 'danger')

// 本批中已被「烘焙中」工单选中的替代烤箱 id（同一替代烤箱不能再接收第二张烘焙中）
const bakingTaken = computed(() => {
  const set = new Set()
  for (const o of orders.value) {
    if (o.status === '烘焙中' && selections[o.id]) set.add(selections[o.id])
  }
  return set
})

// 每张工单的替代烤箱候选项：必须空闲、容量 >= 实际烤盘数；烘焙中工单还要避开本批已选中的炉
function candidates(order) {
  return ovens.value
    .filter((o) => oven.value && o.id !== oven.value.id)
    .map((o) => {
      let disabled = false
      let reason = ''
      if (o.status !== '空闲') {
        disabled = true
        reason = '当前' + o.status
      } else if (o.capacity < order.trays) {
        disabled = true
        reason = '容量不足（需' + order.trays + '盘）'
      } else if (order.status === '烘焙中' && bakingTaken.value.has(o.id) && selections[order.id] !== o.id) {
        disabled = true
        reason = '本批已接收一张烘焙中'
      }
      return { ...o, disabled, reason }
    })
}

function hasCandidate(order) {
  return candidates(order).some((o) => !o.disabled)
}

const selectedOrders = computed(() => orders.value.filter((o) => selections[o.id]))
const selectedBakingCount = computed(() => selectedOrders.value.filter((o) => o.status === '烘焙中').length)

function ovenCode(id) {
  const o = ovens.value.find((x) => x.id === id)
  return o ? o.code : '?'
}

async function open(ovenId) {
  visible.value = true
  await reload(ovenId)
}

async function reload(ovenId) {
  loading.value = true
  try {
    const [ods, ovs, rps] = await Promise.all([
      api.listOvenTakeoverOrders(ovenId),
      api.listOvens(),
      api.listBreadRecipes()
    ])
    orders.value = ods
    ovens.value = ovs
    recipes.value = rps
    oven.value = ovs.find((o) => o.id === ovenId) || null
    Object.keys(selections).forEach((k) => delete selections[k])
    for (const o of ods) selections[o.id] = null
  } catch (e) {
    ElMessage.error(e.message)
    visible.value = false
  } finally {
    loading.value = false
  }
}

async function confirm() {
  if (!oven.value || !selectedOrders.value.length || submitting.value) return
  const n = selectedOrders.value.length
  const b = selectedBakingCount.value
  try {
    await ElMessageBox.confirm(
      `确认将 ${n} 张工单从 ${oven.value.code} 迁出？` +
        (b ? `其中 ${b} 张为「烘焙中」（高风险，保留原开始时间）。` : '全部为「待烤」（低风险）。') +
        '任一张不满足条件将整批不迁移。',
      '确认故障接管',
      { type: 'warning', confirmButtonText: '确认接管', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  submitting.value = true
  const ovenId = oven.value.id
  try {
    const items = selectedOrders.value.map((o) => ({ orderId: o.id, targetOvenId: selections[o.id] }))
    await api.takeoverOven(ovenId, items)
    ElMessage.success(`接管完成：已迁移 ${items.length} 张工单`)
    visible.value = false
    emit('done')
  } catch (e) {
    // 整批未落库：展示能定位到工单与原因的提示，并重新拉取保证页面归属/状态一致
    ElMessageBox.alert(e.message, '接管失败（整批未迁移）', { type: 'error', confirmButtonText: '知道了' })
    await reload(ovenId)
    emit('done')
  } finally {
    submitting.value = false
  }
}

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" width="760px" :close-on-click-modal="false">
    <template #header>
      <div class="dlg-head">
        <span class="dlg-title">烤箱详情 · 故障接管</span>
        <template v-if="oven">
          <el-tag :type="ovenStatusType(oven.status)" effect="dark">{{ oven.status }}</el-tag>
          <span class="dlg-sub">{{ oven.code }} · 容量 {{ oven.capacity }} 盘</span>
        </template>
      </div>
    </template>

    <div v-loading="loading">
      <el-alert
        v-if="oven && oven.status === '故障'"
        type="error"
        :closable="false"
        title="该烤箱当前故障：在炉工单无法开始/继续烘焙，请为每张工单选择替代烤箱整批迁移。"
        class="mb"
      />
      <el-alert
        v-else-if="oven"
        type="info"
        :closable="false"
        title="可将该炉的在炉工单迁移到其他空闲烤箱；待烤迁移后仍为待烤，烘焙中保留原开始时间。"
        class="mb"
      />

      <el-empty v-if="!loading && !orders.length" description="该烤箱当前没有待烤 / 烘焙中工单" :image-size="70" />

      <el-table v-else :data="orders" border size="small">
        <el-table-column label="工单" min-width="150">
          <template #default="{ row }">
            <div class="o-title">#{{ row.id }} · {{ recipeMap[row.recipeId] ? recipeMap[row.recipeId].name : '?' }}</div>
            <div class="o-sub">烤盘 {{ row.trays }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态与风险" width="200">
          <template #default="{ row }">
            <div class="risk-line">
              <el-tag :type="row.status === '烘焙中' ? 'warning' : 'info'" size="small">{{ row.status }}</el-tag>
              <el-tag :type="row.status === '烘焙中' ? 'danger' : 'success'" size="small" effect="plain">
                {{ row.status === '烘焙中' ? '高风险' : '低风险' }}
              </el-tag>
            </div>
            <div class="o-sub" v-if="row.status === '烘焙中'">
              在烤 · 开始于 {{ row.startTime || '-' }}，迁移保留原开始时间
            </div>
            <div class="o-sub" v-else>未入炉，迁移后仍为「待烤」</div>
          </template>
        </el-table-column>
        <el-table-column label="替代烤箱" min-width="230">
          <template #default="{ row }">
            <el-select
              v-model="selections[row.id]"
              placeholder="不迁移"
              clearable
              style="width: 210px"
            >
              <el-option
                v-for="c in candidates(row)"
                :key="c.id"
                :value="c.id"
                :disabled="c.disabled"
                :label="c.code + '（容量' + c.capacity + '）' + (c.disabled ? ' · ' + c.reason : '')"
              />
            </el-select>
            <div class="o-sub warn" v-if="!hasCandidate(row)">暂无可用替代烤箱（需空闲且容量 ≥ {{ row.trays }}）</div>
          </template>
        </el-table-column>
      </el-table>

      <!-- 迁移预览 -->
      <div class="preview" v-if="selectedOrders.length">
        <div class="pv-title">迁移预览（{{ selectedOrders.length }} 张）</div>
        <div class="pv-item" v-for="o in selectedOrders" :key="o.id">
          <el-tag :type="o.status === '烘焙中' ? 'danger' : 'success'" size="small" effect="plain">
            {{ o.status === '烘焙中' ? '高风险' : '低风险' }}
          </el-tag>
          <span class="pv-text">
            #{{ o.id }} {{ recipeMap[o.recipeId] ? recipeMap[o.recipeId].name : '?' }}（{{ o.trays }}盘）
            ：{{ oven ? oven.code : '?' }} → {{ ovenCode(selections[o.id]) }}
          </span>
          <span class="pv-note">{{ o.status === '烘焙中' ? '保持烘焙中 · 原开始时间 ' + (o.startTime || '-') : '仍为待烤' }}</span>
        </div>
        <el-alert
          v-if="selectedBakingCount"
          type="warning"
          :closable="false"
          class="mt"
          :title="'含 ' + selectedBakingCount + ' 张烘焙中工单：需人工转移烤盘并确认替代烤箱炉温，计时不中断。'"
        />
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="danger"
        :disabled="!selectedOrders.length"
        :loading="submitting"
        @click="confirm"
      >
        确认接管（{{ selectedOrders.length }}）
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dlg-head { display: flex; align-items: center; gap: 10px; }
.dlg-title { font-size: 16px; font-weight: 700; }
.dlg-sub { color: var(--muted); font-size: 13px; }
.mb { margin-bottom: 12px; }
.mt { margin-top: 10px; }
.o-title { font-weight: 600; }
.o-sub { font-size: 12px; color: var(--muted); margin-top: 2px; }
.o-sub.warn { color: #d4380d; }
.risk-line { display: flex; gap: 6px; align-items: center; }
.preview { margin-top: 14px; border: 1px dashed var(--primary-soft); border-radius: 10px; padding: 10px 12px; background: #fffdf8; }
.pv-title { font-weight: 700; margin-bottom: 8px; }
.pv-item { display: flex; align-items: center; gap: 8px; padding: 3px 0; flex-wrap: wrap; }
.pv-text { font-size: 13px; }
.pv-note { font-size: 12px; color: var(--muted); }
</style>
