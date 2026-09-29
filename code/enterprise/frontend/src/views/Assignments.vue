<template>
  <div class="assign-page">
    <header><h1 class="page-title">作业管理</h1><p class="page-subtitle">布置 → 点名 → 收作业 → 批改 → 班级报告 → 答题卡（e 卷通）</p></header>

    <el-card style="margin-bottom:16px">
      <h3>新建作业</h3>
      <el-form label-position="top">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="作业标题"><el-input v-model="form.title" maxlength="80" placeholder="如：第一章 集合 课后作业" /></el-form-item></el-col>
          <el-col :span="4"><el-form-item label="学科"><el-select v-model="form.subjectName" style="width:100%"><el-option v-for="s in subjects" :key="s" :label="s" :value="s" /></el-select></el-form-item></el-col>
          <el-col :span="4"><el-form-item label="截止（小时，0=不限）"><el-input-number v-model="form.deadlineHours" :min="0" :max="720" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="题目ID列表（逗号分隔，可从题库详情页复制）">
          <el-input v-model="form.qidsText" placeholder="如 101,102,103" />
        </el-form-item>
        <el-button type="primary" :loading="busy" @click="create">创建（草稿）</el-button>
      </el-form>
    </el-card>

    <el-card>
      <h3>我布置的作业 <el-button size="small" style="float:right" @click="loadMine">刷新</el-button></h3>
      <el-table :data="mine" size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="['info', 'success', 'warning'][row.status]">{{ ['草稿', '已发布', '已关闭'][row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="截止" width="160">
          <template #default="{ row }">{{ row.deadline ? row.deadline.replace('T', ' ').slice(0, 16) : '不限' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="330">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" size="small" type="success" @click="publish(row)">发布</el-button>
            <el-button v-if="row.status === 1" size="small" @click="openRoster(row)">点名</el-button>
            <el-button v-if="row.status === 1" size="small" type="primary" plain @click="makeSheet(row)">答题卡</el-button>
            <el-button size="small" type="warning" plain @click="openReport(row)">班级报告</el-button>
            <el-button v-if="row.status !== 2" size="small" type="danger" plain @click="close(row)">关闭</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!mine.length" description="还没有作业：创建并发布后，把学生 ID 点进名单即可收集作业" />
    </el-card>

    <el-dialog v-model="rosterVisible" title="点名（把学生加入作业名单）" width="min(440px,94vw)">
      <el-input v-model="rosterText" type="textarea" :rows="3" placeholder="学生用户ID，逗号或换行分隔，如 1001,1002" />
      <template #footer>
        <el-button @click="rosterVisible = false">取消</el-button>
        <el-button type="primary" @click="assignRoster">加入名单</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="reportVisible" title="班级报告" size="min(520px,100vw)">
      <template v-if="report">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="名单">{{ report.roster }} 人</el-descriptions-item>
          <el-descriptions-item label="已提交">{{ report.submitted }} 人</el-descriptions-item>
          <el-descriptions-item label="已批改">{{ report.graded }} 人</el-descriptions-item>
          <el-descriptions-item label="平均正确率">{{ report.avgScorePct }}%</el-descriptions-item>
        </el-descriptions>
        <h4 style="margin:14px 0 6px">薄弱知识点（按错误数）</h4>
        <div>
          <el-tag v-for="k in report.weakKnowledgePoints" :key="k.kp" size="small"
                  :type="k.correctRatePct < 60 ? 'danger' : 'info'" style="margin:0 6px 6px 0">
            {{ k.kp }} · 错{{ k.wrong }} · 正确率{{ k.correctRatePct }}%
          </el-tag>
          <span v-if="!report.weakKnowledgePoints?.length" style="font-size:12px;color:var(--text-2)">暂无作答数据</span>
        </div>
        <h4 style="margin:14px 0 6px">逐题正确率</h4>
        <el-table :data="report.questionStats" size="small">
          <el-table-column prop="questionId" label="题目ID" width="90" />
          <el-table-column prop="correct" label="对" width="60" />
          <el-table-column prop="wrong" label="错" width="60" />
          <el-table-column prop="pending" label="待批" width="60" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/request'

const subjects = ['语文', '数学', '英语', '物理', '化学', '生物', '政治', '历史', '地理']
const form = reactive({ title: '', subjectName: '数学', deadlineHours: 168, qidsText: '' })
const busy = ref(false)
const mine = ref([])
const rosterVisible = ref(false), rosterOpen = ref(null), rosterText = ref('')
const reportVisible = ref(false), report = ref(null)

onMounted(loadMine)

async function loadMine() {
  try { mine.value = await http.get('/assignments/mine') } catch { mine.value = [] }
}

async function create() {
  const qids = form.qidsText.split(/[,,\s]+/).map(s => s.trim()).filter(Boolean).map(Number)
  if (!form.title.trim() || !qids.length) { ElMessage.warning('请填写标题和至少一个题目ID'); return }
  busy.value = true
  try {
    await http.post('/assignments', {
      title: form.title.trim(),
      subjectId: subjects.indexOf(form.subjectName) + 1,
      questionIds: qids, deadlineHours: form.deadlineHours
    })
    ElMessage.success('已创建草稿')
    form.title = ''; form.qidsText = ''
    loadMine()
  } catch (_) {} finally { busy.value = false }
}

async function publish(row) {
  await http.post('/assignments/' + row.id + '/publish')
  ElMessage.success('已发布'); loadMine()
}
async function close(row) {
  await http.post('/assignments/' + row.id + '/close')
  ElMessage.success('已关闭'); loadMine()
}
function openRoster(row) { rosterOpen.value = row; rosterText.value = ''; rosterVisible.value = true }
async function assignRoster() {
  const ids = rosterText.value.split(/[^0-9]+/).filter(Boolean).map(Number)
  if (!ids.length) { ElMessage.warning('请输入学生ID'); return }
  const d = await http.post('/assignments/' + rosterOpen.value.id + '/students', { studentIds: ids })
  ElMessage.success(`新增 ${d.added} 人，名单共 ${d.roster} 人`)
  rosterVisible.value = false
}
async function openReport(row) {
  report.value = await http.get('/assignments/' + row.id + '/report')
  reportVisible.value = true
}
async function makeSheet(row) {
  // 下载端点需要 JWT：用 blob 拉取再触发浏览器保存
  const d = await http.get('/assignments/' + row.id + '/answer-sheet')
  const raw = await fetch(d.downloadUrl, { headers: { Authorization: 'Bearer ' + localStorage.getItem('examforge_token') } })
  if (!raw.ok) { ElMessage.error('答题卡下载失败'); return }
  const blob = await raw.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'answer-sheet-' + row.id + '.' + (d.format === 'PDF' ? 'pdf' : 'html')
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success(`答题卡已生成（${d.format}，${d.questionCount} 题）`)
}
</script>

<style scoped>
.assign-page{max-width:1250px;margin:auto}
</style>
