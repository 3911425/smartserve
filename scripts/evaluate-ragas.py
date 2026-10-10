"""Optional RAGAS evaluation of reviewed, actually generated answers. Does not invent results.
Install dependencies in an isolated venv; provide data/evaluation/answers.json yourself or from API runs.
Each row: question, answer, contexts (list[str]), ground_truth.
"""
import json
import os
from pathlib import Path
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import faithfulness, answer_relevancy
from ragas.llms import LangchainLLMWrapper
from ragas.embeddings import LangchainEmbeddingsWrapper
from langchain_openai import ChatOpenAI
from langchain_huggingface import HuggingFaceEmbeddings

root = Path(__file__).resolve().parent.parent
for line in (root / '.env').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        os.environ.setdefault(key, value)
source = root / 'data/evaluation/answers.json'
if not source.exists():
    raise SystemExit('请先导出实际问答结果和人工参考答案到 data/evaluation/answers.json；不会预填或编造分数。')
llm = LangchainLLMWrapper(ChatOpenAI(model=os.getenv('DEEPSEEK_MODEL', 'deepseek-chat'), api_key=os.environ['DEEPSEEK_API_KEY'], base_url=os.getenv('DEEPSEEK_BASE_URL', 'https://api.deepseek.com'), temperature=0))
embeddings = LangchainEmbeddingsWrapper(HuggingFaceEmbeddings(model_name='BAAI/bge-small-zh-v1.5'))
result = evaluate(Dataset.from_list(json.loads(source.read_text(encoding='utf-8'))), metrics=[faithfulness, answer_relevancy], llm=llm, embeddings=embeddings, raise_exceptions=False)
result.to_pandas().to_csv(root / 'docs/ragas-results.csv', index=False)
print('已保存实际评估结果到 docs/ragas-results.csv，请检查失败行和人工复核。')
