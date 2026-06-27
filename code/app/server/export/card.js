// 答题卡生成（docs/25 TJ-110，对标组卷网 e卷通"答题卡一键生成"）：
// 学生信息栏 + 客观题涂卡矩阵（按题目选项数自适应 A-D/判断 T-F）+ 填空横线区 + 解答空白框 + 水印。
// 纯服务端 HTML 模板，打印即用；无外部依赖。
function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;'); }

function buildAnswerCardHtml(paper, watermark) {
  const objective = []; // 涂卡矩阵行
  const subjective = []; // 填空/解答留白区
  paper.questions.forEach((q, i) => {
    const no = i + 1;
    if (q.type === '单选题' || q.type === '多选题') {
      let opts = ['A', 'B', 'C', 'D'];
      try { const o = JSON.parse(q.options || 'null'); if (Array.isArray(o) && o.length) opts = o.map(x => x.l); } catch (_) {}
      if (q.type === '多选题') opts = opts.concat(['E', 'F'].slice(0, Math.max(0, 6 - opts.length)));
      objective.push({ no, type: q.type, opts: opts.slice(0, q.type === '判断题' ? 2 : opts.length) });
    } else if (q.type === '判断题') {
      objective.push({ no, type: q.type, opts: ['T', 'F'] });
    } else if (q.type === '填空题') {
      subjective.push({ no, type: q.type, lines: 2 });
    } else {
      subjective.push({ no, type: q.type, lines: 0, box: true });
    }
  });

  // 涂卡矩阵：每行最多 5 题，网格排版
  const perRow = 5;
  const gridRows = [];
  for (let i = 0; i < objective.length; i += perRow) {
    gridRows.push(objective.slice(i, i + perRow));
  }
  const gridHtml = gridRows.map(row => `<tr>${row.map(it => `
    <td class="cell">
      <div class="no">${it.no}${it.type === '多选题' ? '<i class="multi">多选</i>' : ''}</div>
      <div class="opts">${it.opts.map(o => `<span class="bubble">${o}</span>`).join('')}</div>
    </td>`).join('')}${row.length < perRow ? '<td class="cell empty" colspan="' + (perRow - row.length) + '"></td>' : ''}</tr>`).join('');

  const subjectiveHtml = subjective.map(it => it.box
    ? `<div class="sub-block"><div class="sub-no">${it.no}. 解答区</div><div class="blank-box"></div><div class="blank-box short"></div></div>`
    : `<div class="sub-block"><div class="sub-no">${it.no}. 填空区</div><div class="fill-line"></div><div class="fill-line"></div></div>`).join('');

  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><title>${esc(paper.title)} · 答题卡</title>
<style>
body{font-family:'SimHei','Microsoft YaHei',sans-serif;max-width:760px;margin:24px auto;padding:0 16px;color:#111}
h1{text-align:center;font-size:20px;margin:6px 0}
.head{display:flex;justify-content:space-between;align-items:center;border-bottom:2px solid #111;padding-bottom:8px}
.head .info{font-size:14px}.head .info span{margin-right:22px}
.head .info i{display:inline-block;border-bottom:1px solid #111;min-width:110px;font-style:normal}
.sect{margin:14px 0 6px;font-size:14px;font-weight:700;border-left:4px solid #111;padding-left:8px}
table{width:100%;border-collapse:collapse}
td.cell{border:1px solid #999;padding:8px 10px;vertical-align:top}
td.empty{border:none}
.no{font-size:13px;font-weight:700;margin-bottom:4px}
.no .multi{font-style:normal;font-size:10px;color:#b91c1c;margin-left:4px}
.opts .bubble{display:inline-block;width:22px;height:22px;line-height:20px;text-align:center;border:1.2px solid #333;border-radius:50%;font-size:11px;margin-right:6px}
.sub-block{margin:10px 0;page-break-inside:avoid}
.sub-no{font-size:13px;font-weight:700;margin-bottom:6px}
.fill-line{border-bottom:1px solid #333;height:26px;margin:0 10px 8px}
.blank-box{border:1px solid #333;height:150px;margin-bottom:8px}
.blank-box.short{height:90px}
.wm{margin-top:26px;color:#94a3b8;font-size:12px;text-align:center;border-top:1px solid #e2e8f0;padding-top:8px}
.notice{background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;font-size:12px;color:#475569;padding:8px 12px;margin:10px 0}
</style></head><body>
<div class="head"><h1>${esc(paper.title)} · 答题卡</h1><div class="info">
<span>姓名 <i></i></span><span>考号 <i></i></span><span>班级 <i></i></span></div></div>
<div class="notice">填涂说明：客观题请用 2B 铅笔将对应字母涂黑；多选题每题可多涂；填空题书写工整、解答题在框内作答，超出区域无效。</div>
<div class="sect">一、客观题涂卡区</div>
<table><tbody>${gridHtml || '<tr><td class="cell">本卷无客观题</td></tr>'}</tbody></table>
<div class="sect">二、填空与解答区</div>
${subjectiveHtml || '<p class="muted" style="font-size:13px">本卷无填空/解答题</p>'}
<div class="wm">${esc(watermark)}</div>
</body></html>`;
}

module.exports = { buildAnswerCardHtml };
