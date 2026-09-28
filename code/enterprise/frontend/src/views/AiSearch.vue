<template>
  <div>
    <el-card style="max-width:860px;margin:20px auto">
      <h3>🔍 AI 搜 · 自然语言搜题</h3>
      <div style="display:flex;gap:10px;margin-top:10px">
        <el-input v-model="query" placeholder="例：高二 立体几何 解答题 较难" @keyup.enter="doSearch" />
        <el-button type="primary" @click="doSearch">AI 搜</el-button>
      </div>
      <p class="meta" style="margin-top:8px">AI 自动解析难度/题型/知识点后检索；0 结果时可用「AI 出同款」</p>
      <el-alert v-if="parsed" :title="'已解析：' + JSON.stringify(parsed)" type="info" :closable="false" style="margin-top:10px" />
    </el-card>

    <div style="max-width:860px;margin:0 auto" v-if="list.length">
      <div class="q-card" v-for="q in list" :key="q.id">
        <div class="meta">{{ q.type }} ｜ 难度 {{ q.difficulty }} ｜ 系数 {{ q.coefficient }} ｜ {{ q.kpNames }}</div>
        <div class="q-stem" v-html="render(q.stem)"></div>
        <el-button size="small" style="margin-top:8px" @click="$router.push('/questions/' + q.id)">查看详情与解析</el-button>
      </div>
      <el-empty v-if="!list.length" description="没有找到合适题目 —— 可用「AI 出同款」直接生成" />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import katex from 'katex'
import http from '../api/request'

const query = ref(''), list = ref([]), parsed = ref(null)

async function doSearch() {
  const d = await http.post('/ai/search', { query: query.value })
  parsed.value = d.parsed
  list.value = d.list
}
function render(s) {
  if (!s) return ''
  return String(s).replace(/\\\((.+?)\\\)/gs, (_, tex) => {
    try { return katex.renderToString(tex, { throwOnError: false }) } catch (_) { return _ }
  })
}
</script>
