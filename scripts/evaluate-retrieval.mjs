import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const base=process.env.SMARTSERVE_BASE_URL||'http://127.0.0.1:8080';
let token=process.env.SMARTSERVE_API_TOKEN;
if(!token){const response=await fetch(base+'/api/demo/session',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({role:'customer'})});if(!response.ok)throw new Error('请设置SMARTSERVE_API_TOKEN，或在本地演示环境运行');token=(await response.json()).token;}
const headers={Authorization:'Bearer '+token};
const cases=JSON.parse(await fs.readFile(path.join(root,'data/evaluation/retrieval-cases.json'),'utf8'));
const metrics=[],records=[];
for(const strategy of ['vector','bm25','hybrid']){
  let recall=0,mrr=0,n=0,falseAccepted=0,neg=0;
  for(const item of cases){const response=await fetch(base+'/api/knowledge/search?'+new URLSearchParams({query:item.question,strategy}),{headers});if(!response.ok)throw new Error('检索请求失败');const results=await response.json();const headings=results.map(r=>r.heading);if(item.negative){neg++;if(results.some(r=>r.accepted))falseAccepted++;}else{n++;const hits=item.relevant.filter(h=>headings.includes(h));recall+=hits.length/item.relevant.length;const first=results.findIndex(r=>item.relevant.includes(r.heading));mrr+=first<0?0:1/(first+1);}records.push({strategy,question:item.question,relevant:item.relevant,headings,accepted:results.filter(r=>r.accepted).map(r=>r.heading)});}
  metrics.push({strategy,positiveCases:n,recallAt5:Number((recall/n).toFixed(4)),mrrAt5:Number((mrr/n).toFixed(4)),negativeCases:neg,falseAccepted});
}
const status=await fetch(base+'/api/status').then(r=>r.json());
const report={measuredAt:new Date().toISOString(),knowledge:status.knowledge,dataset:'data/evaluation/retrieval-cases.json',metrics,records,limitations:['小型固定样例集，相关标签非穷尽','调参集与保留测试集尚未分离','负样本门槛独立于召回排序策略，不能把排序差异视为护栏差异','未测LLM忠实度或生产负载']};
await fs.writeFile(path.join(root,'docs/retrieval-results.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({knowledge:status.knowledge,metrics},null,2));
