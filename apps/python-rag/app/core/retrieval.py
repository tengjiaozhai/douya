from __future__ import annotations

import hashlib
import math
from collections import Counter

from app.indexing.chunker import tokenize


def l2_normalize(vec: list[float]) -> list[float]:
    """把向量长度缩放到 1（L2 归一化）。

    直观理解：
    - 向量像“箭头”。
    - 归一化后只保留“方向”，弱化“长度”差异。

    小例子：
    - 输入 `[3, 4]`，长度是 `5`
    - 输出 `[0.6, 0.8]`
    """
    # 向量长度公式：sqrt(v1^2 + v2^2 + ...)
    norm = math.sqrt(sum(v * v for v in vec))
    # 全 0 向量没有方向，直接原样返回。
    if norm == 0:
        return vec
    # 每个维度都除以长度，得到单位向量。
    return [v / norm for v in vec]


def hash_embedding(text: str, dim: int = 384) -> list[float]:
    """把文本映射成定长向量（简化版 embedding，不依赖外部模型）。

    设计动机：
    - 不需要联网，不需要下载大模型。
    - 可重复：同一段文本每次都会得到同样结果。

    核心思路（类似“把词丢进固定数量的篮子”）：
    1) 先把文本切成 token。
    2) 每个 token 计算 sha256。
    3) 用 hash 值决定落到哪个维度（篮子）。
    4) 再用 hash 值决定 +1 还是 -1（减少单向偏置）。
    5) 最后做归一化。

    小例子（dim=8）：
    - tokenA 可能落到维度 3，加 +1
    - tokenB 可能也落到维度 3，但加 -1
    - 这样维度 3 的值会部分抵消，减少碰撞噪声。
    """
    # 把文本切成词项。
    tokens = tokenize(text)
    # 初始化全 0 向量，长度由 dim 决定。
    vec = [0.0] * dim
    # 空文本直接返回全 0 向量。
    if not tokens:
        return vec
    # 遍历每个 token，把它“投影”到某一维。
    for t in tokens:
        # 计算 token 的稳定哈希（同 token 永远相同）。
        digest = hashlib.sha256(t.encode("utf-8")).hexdigest()
        # 取前 8 位十六进制转整数，再取模，得到维度索引。
        idx = int(digest[:8], 16) % dim
        # 再取后续 2 位决定符号：偶数 +1，奇数 -1。
        sign = 1.0 if int(digest[8:10], 16) % 2 == 0 else -1.0
        # 对应维度累加。
        vec[idx] += sign
    # 最后归一化，便于和其他向量做余弦相似度。
    return l2_normalize(vec)


def cosine_similarity(a: list[float], b: list[float]) -> float:
    """计算余弦相似度（这里输入向量通常已归一化）。

    小例子：
    - a=[1,0], b=[1,0] -> 1.0（方向完全一致）
    - a=[1,0], b=[0,1] -> 0.0（方向垂直）
    """
    # 长度不一致或空向量，直接返回 0。
    if len(a) != len(b) or not a:
        return 0.0
    # 归一化向量的点积就是余弦相似度。
    return sum(x * y for x, y in zip(a, b))


def sparse_terms(text: str) -> dict[str, float]:
    """把文本转换为稀疏词项权重（TF）。

    返回格式：
    - key: 词项
    - value: 词频占比（term frequency）

    小例子：
    - 文本："苹果 苹果 香蕉"
    - 结果：{"苹果": 2/3, "香蕉": 1/3}
    """
    # 切词。
    terms = tokenize(text)
    # 没词就返回空对象。
    if not terms:
        return {}
    # 统计每个词出现次数。
    tf = Counter(terms)
    # 总词数转浮点，后续用于归一化。
    total = float(len(terms))
    # 转成“占比”而不是“绝对次数”，便于不同长度文本比较。
    return {k: v / total for k, v in tf.items()}


def sparse_similarity(
    query_terms: dict[str, float],
    doc_terms: dict[str, float],
    idf: dict[str, float],
) -> float:
    """计算稀疏相似度（TF * TF * IDF 的加权和）。

    只对“查询和文档都出现的词”加分。

    小例子：
    - query_terms={"钾":0.5,"香蕉":0.5}
    - doc_terms={"钾":0.2,"苹果":0.8}
    - 只有“钾”重叠，因此只加“钾”这一项的分数。
    """
    # 初始化总分。
    score = 0.0
    # 遍历查询里的每个词。
    for term, q_tf in query_terms.items():
        # 取文档里该词的权重；没有就跳过。
        d_tf = doc_terms.get(term)
        if d_tf is None:
            continue
        # 重叠词贡献：查询词频 * 文档词频 * 逆文档频率。
        score += q_tf * d_tf * idf.get(term, 1.0)
    # 返回总分。
    return score


def keyword_match_score(
    query_tokens: list[str],
    doc_terms: dict[str, float],
    idf: dict[str, float],
) -> float:
    """计算关键词匹配分（强调“字面命中”能力）。

    设计目标：
    - 和 dense（语义）互补：同义词用 dense，精确术语用 keyword。
    - 和 sparse（TF*TF*IDF）互补：keyword 更强调“覆盖了哪些查询词”。

    评分思路：
    1) 先算“覆盖率”：查询词中有多少被命中（按 IDF 加权）。
    2) 再算“词频密度”：命中词在文档中的局部强度（平均 TF）。
    3) 线性融合（覆盖率主导，密度辅助）。
    """
    # 查询或文档为空时，无法做关键词匹配。
    if not query_tokens or not doc_terms:
        return 0.0
    # 查询按“去重词”统计覆盖率，避免重复词放大影响。
    query_set = set(query_tokens)
    # 求命中词集合（查询词与文档词交集）。
    hit_terms = query_set & set(doc_terms.keys())
    # 一个都没命中，直接 0 分。
    if not hit_terms:
        return 0.0

    # 覆盖率（IDF 加权）：
    # - 稀有词（IDF 高）命中价值更大。
    total_idf = sum(idf.get(term, 1.0) for term in query_set)
    hit_idf = sum(idf.get(term, 1.0) for term in hit_terms)
    weighted_coverage = (hit_idf / total_idf) if total_idf > 0 else 0.0

    # 词频密度：命中词在文档中的平均 TF（作为辅助信号）。
    tf_density = sum(doc_terms.get(term, 0.0) for term in hit_terms) / float(len(hit_terms))

    # 覆盖率优先，密度次之。
    return weighted_coverage * 0.85 + tf_density * 0.15


def build_idf(all_docs_terms: list[dict[str, float]]) -> dict[str, float]:
    """根据语料构建每个词的 IDF。

    直观理解：
    - 越“稀有”的词，区分度越高，IDF 越大。
    - 越“常见”的词（几乎每篇都出现），IDF 越小。

    小例子：
    - 100 个文档里，“钾”只出现 3 次 -> IDF 较高
    - “的”出现 95 次 -> IDF 较低
    """
    # 文档总数，至少按 1 计算，避免除零问题。
    n = max(len(all_docs_terms), 1)
    # df: document frequency，记录“包含该词的文档数”。
    df: Counter[str] = Counter()
    # 遍历每个文档的词典。
    for terms in all_docs_terms:
        # 这里按“文档是否包含词”计数，不看词出现次数。
        for key in terms.keys():
            df[key] += 1
    # BM25 常见的平滑 IDF 形式，避免极端值。
    return {k: math.log(1 + (n - v + 0.5) / (v + 0.5)) for k, v in df.items()}


def rrf_fusion_multi(rank_routes: list[dict[str, int]], k: int) -> dict[str, float]:
    """用 RRF（Reciprocal Rank Fusion）融合多路排序结果。

    公式：
    - 分数 = Σ(1 / (k + route_rank))
    - 某一路没命中就当该路贡献为 0。

    为什么用“名次”而不是“原始分值”：
    - 不同检索器的分值尺度常常不一致，直接相加不公平。
    - 名次更稳定，融合更鲁棒。
    """
    # 取并集：只要任一路出现过，都进入融合候选集。
    keys: set[str] = set()
    for route in rank_routes:
        keys.update(route.keys())
    # 保存融合后的最终分数。
    fused: dict[str, float] = {}
    # 逐项计算融合分。
    for key in keys:
        total = 0.0
        # 累加每一路的倒数名次分。
        for route in rank_routes:
            rank = route.get(key)
            if rank is None:
                continue
            total += 1.0 / (k + rank)
        fused[key] = total
    # 返回 {chunk_id: fused_score}。
    return fused


def rrf_fusion(ranks_dense: dict[str, int], ranks_sparse: dict[str, int], k: int) -> dict[str, float]:
    """兼容旧接口：融合两路排序。"""
    return rrf_fusion_multi([ranks_dense, ranks_sparse], k)
